package com.interviewlab.workbench.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    classes = VoiceAsrRelayIntegrationTest.TestServer.class,
    properties = "spring.datasource.url=jdbc:h2:mem:voice-asr-relay;DB_CLOSE_DELAY=-1")
@ActiveProfiles("voice-relay-test")
class VoiceAsrRelayIntegrationTest {
    private static final int PORT = temporaryPort();
    private static final byte[] PCM = {0x01, 0x02, 0x03, 0x04};

    @Autowired
    private JdbcTemplate database;

    @DynamicPropertySource
    static void port(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
    }

    @Test
    void relaysPcmAndDeliversTheFinalResultAfterFinishing() throws Exception {
        String interview = seed(false);
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        WebSocket client = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Origin", "http://127.0.0.1:" + PORT)
            .buildAsync(route("/api/voice/asr?session=" + interview), collect(events)).get(5, TimeUnit.SECONDS);
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"ready\"");
            client.sendBinary(ByteBuffer.wrap(PCM), true).get(5, TimeUnit.SECONDS);
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"text\":\"临时字幕\"", "\"complete\":false");
            client.sendText("finish", true).get(5, TimeUnit.SECONDS);
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"text\":\"最终字幕\"", "\"complete\":true");
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"done\"");
        } finally {
            client.abort();
        }
    }

    @Test
    void rejectsExternalOriginsAndPausedInterviews() throws Exception {
        String interview = seed(true);
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = route("/api/voice/asr?session=" + interview);
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        assertThatThrownBy(() -> client.newWebSocketBuilder().header("Origin", "http://other.invalid")
            .buildAsync(endpoint, collect(events)).get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(WebSocketHandshakeException.class);
        WebSocket paused = client.newWebSocketBuilder().buildAsync(endpoint, collect(events)).get(5, TimeUnit.SECONDS);
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"type\":\"error\"", "进行中的语音面试");
        } finally {
            paused.abort();
        }
    }

    private String seed(boolean paused) {
        String id = UUID.randomUUID().toString();
        database.update("INSERT INTO interview_session (id, role_code, next_index, kind_code, paused) VALUES (?, 'java', 0, 'voice', ?)",
            id, paused);
        return id;
    }

    private static WebSocket.Listener collect(BlockingQueue<String> events) {
        return new WebSocket.Listener() {
            private final StringBuilder frames = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket socket, CharSequence frame, boolean last) {
                frames.append(frame);
                if (last) {
                    events.add(frames.toString());
                    frames.setLength(0);
                }
                socket.request(1);
                return null;
            }
        };
    }

    private static URI route(String path) {
        return URI.create("ws://127.0.0.1:" + PORT + path);
    }

    private static int temporaryPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot allocate a port for the relay test", failure);
        }
    }

    @Profile("voice-relay-test")
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableWebSocket
    static class TestServer implements WebSocketConfigurer {
        private final VoiceAsrRelay relay;
        private final ObjectMapper json;

        TestServer(JdbcTemplate database, ObjectMapper json, @Value("${server.port}") int port) {
            this.json = json;
            var provider = URI.create("ws://127.0.0.1:" + port + "/mock-asr");
            this.relay = new VoiceAsrRelay(HttpClient.newHttpClient(), provider, "test-asr-token", json, database);
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(relay, "/api/voice/asr");
            registry.addHandler(new AbstractWebSocketHandler() {
                @Override
                protected void handleTextMessage(WebSocketSession provider, TextMessage request) throws Exception {
                    var header = json.readTree(request.getPayload()).path("header");
                    String taskId = header.path("task_id").asText();
                    assertThat(provider.getHandshakeHeaders().getFirst("Authorization")).isEqualTo("Bearer test-asr-token");
                    if (header.path("action").asText().equals("run-task")) {
                        provider.getAttributes().put("task", taskId);
                        deliver(provider, "task-started", "{}", taskId);
                    } else {
                        assertThat(header.path("action").asText()).isEqualTo("finish-task");
                        deliver(provider, "result-generated", sentence("最终字幕", true), taskId);
                        deliver(provider, "task-finished", "{}", taskId);
                    }
                }

                @Override
                protected void handleBinaryMessage(WebSocketSession provider, BinaryMessage audio) throws Exception {
                    byte[] bytes = new byte[audio.getPayloadLength()];
                    audio.getPayload().get(bytes);
                    assertThat(bytes).isEqualTo(PCM);
                    deliver(provider, "result-generated", sentence("临时字幕", false), (String) provider.getAttributes().get("task"));
                }

                private void deliver(WebSocketSession provider, String event, String payload, String taskId) throws Exception {
                    provider.sendMessage(new TextMessage("""
                        {"header":{"task_id":"%s","event":"%s"},"payload":%s}
                        """.formatted(taskId, event, payload)));
                }

                private String sentence(String value, boolean complete) {
                    return """
                        {"output":{"sentence":{"text":"%s","sentence_end":%s}}}
                        """.formatted(value, complete);
                }
            }, "/mock-asr");
        }
    }
}
