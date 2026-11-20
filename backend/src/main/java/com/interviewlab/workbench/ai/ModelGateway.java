package com.interviewlab.workbench.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ModelGateway {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json;
    private final String key;
    private final String base;
    private final String chat;
    private final String embedding;

    public ModelGateway(ObjectMapper json,
                        @Value("${workbench.model.api-key}") String key,
                        @Value("${workbench.model.base-url}") String base,
                        @Value("${workbench.model.chat}") String chat,
                        @Value("${workbench.model.embedding}") String embedding) {
        this.json = json;
        this.key = key.strip();
        this.base = base.strip().replaceAll("/+$", "");
        this.chat = chat.strip();
        this.embedding = embedding.strip();
    }

    public boolean enabled() {
        return !key.isEmpty() && !base.isEmpty() && !chat.isEmpty();
    }

    public boolean embeddingsEnabled() {
        return enabled() && !embedding.isEmpty();
    }

    public String mode() {
        return enabled() ? "model" : "demo";
    }

    public String embeddingSpace() {
        return base + "/" + embedding;
    }

    public String complete(String instruction, String data) {
        var request = Map.of("model", chat, "messages", List.of(
            Map.of("role", "system", "content", instruction),
            Map.of("role", "user", "content", data)
        ));
        String content = post("/chat/completions", request)
            .path("choices").path(0).path("message").path("content").asText("").strip();
        if (content.isEmpty()) {
            throw invalidResponse();
        }
        return content;
    }

    public List<double[]> embed(List<String> texts) {
        var results = new ArrayList<double[]>();
        for (int start = 0; start < texts.size(); start += 8) {
            List<String> batch = texts.subList(start, Math.min(start + 8, texts.size()));
            JsonNode data = post("/embeddings", Map.of("model", embedding, "input", batch)).path("data");
            if (!data.isArray() || data.size() != batch.size()) {
                throw invalidResponse();
            }
            double[][] ordered = new double[batch.size()][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode coordinates = item.path("embedding");
                if (index < 0 || index >= ordered.length || ordered[index] != null
                        || !coordinates.isArray() || coordinates.isEmpty()) {
                    throw invalidResponse();
                }
                double[] vector = new double[coordinates.size()];
                for (int i = 0; i < vector.length; i++) {
                    JsonNode coordinate = coordinates.get(i);
                    if (!coordinate.isNumber() || !Double.isFinite(coordinate.asDouble())) {
                        throw invalidResponse();
                    }
                    vector[i] = coordinate.asDouble();
                }
                ordered[index] = vector;
            }
            int dimensions = ordered[0].length;
            if (Arrays.stream(ordered).anyMatch(vector -> vector.length != dimensions)
                    || (!results.isEmpty() && results.getFirst().length != dimensions)) {
                throw invalidResponse();
            }
            results.addAll(Arrays.asList(ordered));
        }
        return results;
    }

    private JsonNode post(String route, Object payload) {
        if (!enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置模型连接。");
        }
        try {
            URI endpoint = URI.create(base + route);
            if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                    || endpoint.getFragment() != null || !List.of("https", "http").contains(endpoint.getScheme())) {
                throw new IllegalArgumentException("invalid endpoint");
            }
            var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "模型服务返回 HTTP " + response.statusCode() + "，请检查配置或稍后重试。");
            }
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw invalidResponse();
            }
            return parsed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用已中断，请重试。");
        } catch (IOException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型连接失败，请检查配置和网络。");
        }
    }

    private ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回的数据格式不符合预期。");
    }
}
