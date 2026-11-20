package com.interviewlab.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.ai.ModelGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelWireTest {
    @Test
    void modelGatewaySendsCompatiblePayloadAndRestoresEmbeddingOrder() throws Exception {
        var json = new ObjectMapper();
        var requestMatches = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var body = json.readTree(exchange.getRequestBody());
            requestMatches.set("POST".equals(exchange.getRequestMethod())
                && "Bearer unit-test".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                && "test-chat".equals(body.path("model").asText())
                && "user".equals(body.path("messages").path(1).path("role").asText())
                && "设备巡检".equals(body.path("messages").path(1).path("content").asText()));
            send(exchange, 200, """
                {"choices":[{"message":{"content":"按每周计划执行 [1]"}}]}
                """);
        });
        server.createContext("/v1/embeddings", exchange -> send(exchange, 200, """
            {"data":[{"index":1,"embedding":[0,1]},{"index":0,"embedding":[1,0]}]}
            """));
        server.start();
        try {
            var gateway = new ModelGateway(json, "unit-test",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-chat", "test-embed");
            assertThat(gateway.complete("中文回答", "设备巡检")).isEqualTo("按每周计划执行 [1]");
            assertThat(requestMatches).isTrue();
            List<double[]> vectors = gateway.embed(List.of("one", "two"));
            assertThat(vectors.get(0)).containsExactly(1, 0);
            assertThat(vectors.get(1)).containsExactly(0, 1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerErrorDoesNotExposeResponseBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/chat/completions", exchange -> send(exchange, 401, "server-only diagnostic"));
        server.start();
        try {
            var gateway = new ModelGateway(new ObjectMapper(), "unit-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-chat", "");
            assertThatThrownBy(() -> gateway.complete("instruction", "message"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401")
                .hasMessageNotContaining("server-only diagnostic");
        } finally {
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
