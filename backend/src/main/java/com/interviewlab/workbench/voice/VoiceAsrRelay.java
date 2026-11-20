package com.interviewlab.workbench.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.util.UriComponentsBuilder;

final class VoiceAsrRelay extends AbstractWebSocketHandler {
    private static final int MAX_FRAME_BYTES = 3_200;
    private static final long MAX_AUDIO_BYTES = 16_000 * 2L * 125;
    private static final long MAX_CONNECTION_SECONDS = 140;

    private final HttpClient http;
    private final URI endpoint;
    private final String key;
    private final ObjectMapper json;
    private final VoiceAsrProtocol protocol;
    private final JdbcTemplate database;
    private final Map<String, Stream> connections = new ConcurrentHashMap<>();
    private final Map<String, Stream> interviews = new ConcurrentHashMap<>();

    VoiceAsrRelay(HttpClient http, URI endpoint, String key, ObjectMapper json, JdbcTemplate database) {
        this.http = http;
        this.endpoint = endpoint;
        this.key = key;
        this.json = json;
        this.protocol = new VoiceAsrProtocol(json);
        this.database = database;
    }

    boolean enabled() {
        return endpoint != null && !key.isBlank();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!enabled()) {
            reject(session, "云端转写尚未配置，可切换浏览器转写或输入回答。");
            return;
        }
        String interviewId = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("session");
        if (interviewId == null || !interviewId.matches("[0-9a-fA-F-]{36}") || !canRecord(interviewId)) {
            reject(session, "请在进行中的语音面试内录音。");
            return;
        }
        session.setBinaryMessageSizeLimit(MAX_FRAME_BYTES);
        session.setTextMessageSizeLimit(256);
        Stream stream = new Stream(session, interviewId);
        if (interviews.putIfAbsent(interviewId, stream) != null) {
            reject(session, "该面试已有一个转写连接，请关闭其他页面的录音后重试。");
            return;
        }
        connections.put(session.getId(), stream);
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + key)
            .buildAsync(endpoint, stream).orTimeout(10, TimeUnit.SECONDS).exceptionally(cause -> {
                stream.fail("无法连接云端转写服务，请检查百炼配置后重试。");
                return null;
            });
        CompletableFuture.delayedExecutor(MAX_CONNECTION_SECONDS, TimeUnit.SECONDS)
            .execute(() -> stream.fail("本次转写连接已到时限，可以重新录音。"));
    }

    private boolean canRecord(String interviewId) {
        return Boolean.TRUE.equals(database.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM interview_session WHERE id = ? AND kind_code = 'voice'
                AND paused = FALSE AND completed_at IS NULL)
            """, Boolean.class, interviewId));
    }

    private void reject(WebSocketSession session, String message) throws IOException {
        session.sendMessage(new TextMessage(json.writeValueAsString(new VoiceAsrProtocol.Event("error", message, false))));
        session.close(CloseStatus.POLICY_VIOLATION);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage audio) {
        Stream stream = connections.get(session.getId());
        if (stream != null) {
            stream.sendAudio(audio.getPayload());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Stream stream = connections.get(session.getId());
        if (stream != null && message.getPayload().equals("finish")) {
            stream.finish();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Stream stream = connections.get(session.getId());
        if (stream != null) {
            stream.close();
        }
    }

    private final class Stream implements WebSocket.Listener {
        private final WebSocketSession browser;
        private final String interviewId;
        private final String taskId = UUID.randomUUID().toString();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final StringBuilder receiveBuffer = new StringBuilder();
        private volatile WebSocket provider;
        private volatile boolean ready;
        private boolean finishing;
        private long receivedBytes;

        Stream(WebSocketSession browser, String interviewId) {
            this.browser = new ConcurrentWebSocketSessionDecorator(browser, 3_000, 65_536);
            this.interviewId = interviewId;
        }

        @Override
        public void onOpen(WebSocket socket) {
            provider = socket;
            if (closed.get()) {
                socket.abort();
                return;
            }
            socket.request(1);
            socket.sendText(protocol.start(taskId), true).orTimeout(3, TimeUnit.SECONDS).exceptionally(cause -> {
                fail("云端转写任务无法启动。");
                return null;
            });
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence frame, boolean last) {
            if (receiveBuffer.length() + frame.length() > 131_072) {
                fail("云端转写返回过长的数据。");
                return null;
            }
            receiveBuffer.append(frame);
            if (last) {
                try {
                    var event = protocol.read(receiveBuffer.toString(), taskId);
                    if (event != null && !closed.get()) {
                        switch (event.type()) {
                            case "ready" -> {
                                ready = true;
                                toBrowser(event);
                            }
                            case "done" -> {
                                toBrowser(event);
                                close();
                            }
                            case "error" -> fail(event.text());
                            default -> toBrowser(event);
                        }
                    }
                } catch (IOException | RuntimeException failure) {
                    fail("云端转写结果读取失败。");
                } finally {
                    receiveBuffer.setLength(0);
                }
            }
            if (!closed.get()) {
                socket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            fail("云端转写连接已中断，已识别的文字会保留。");
            return null;
        }

        @Override
        public void onError(WebSocket socket, Throwable failure) {
            fail("云端转写连接发生错误，已识别的文字会保留。");
        }

        void sendAudio(ByteBuffer frame) {
            if (!ready || finishing || closed.get()) {
                return;
            }
            receivedBytes += frame.remaining();
            if (frame.remaining() % 2 != 0 || receivedBytes > MAX_AUDIO_BYTES) {
                fail("录音数据超出当前转写的限制，可以重新录音。");
                return;
            }
            try {
                provider.sendBinary(frame, true).orTimeout(3, TimeUnit.SECONDS).join();
            } catch (RuntimeException failure) {
                fail("无法发送录音，已识别的文字会保留。");
            }
        }

        void finish() {
            if (!ready || finishing || closed.get()) {
                return;
            }
            finishing = true;
            provider.sendText(protocol.finish(taskId), true).orTimeout(3, TimeUnit.SECONDS).exceptionally(cause -> {
                fail("云端转写未能正常结束，已识别的文字会保留。");
                return null;
            });
        }

        void toBrowser(VoiceAsrProtocol.Event event) throws IOException {
            if (browser.isOpen()) {
                browser.sendMessage(new TextMessage(json.writeValueAsString(event)));
            }
        }

        void fail(String message) {
            try {
                if (!closed.get()) {
                    toBrowser(new VoiceAsrProtocol.Event("error", message, false));
                }
            } catch (IOException | RuntimeException ignored) {
                // The browser may have disconnected while the upstream event was in flight.
            } finally {
                close();
            }
        }

        void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            connections.remove(browser.getId(), this);
            interviews.remove(interviewId, this);
            if (provider != null) {
                provider.abort();
            }
            try {
                browser.close();
            } catch (IOException ignored) {
                // The upstream is already closed and no audio remains in the relay.
            }
        }
    }
}
