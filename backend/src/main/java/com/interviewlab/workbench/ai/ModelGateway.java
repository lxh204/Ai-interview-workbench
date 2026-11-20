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
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ModelGateway {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ObjectMapper json;
    private volatile Configuration configuration;

    public ModelGateway(ObjectMapper json,
                        @Value("${workbench.model.api-key}") String key,
                        @Value("${workbench.model.base-url}") String base,
                        @Value("${workbench.model.chat}") String chat,
                        @Value("${workbench.model.embedding}") String embedding) {
        this.json = json;
        this.configuration = new Configuration(cleanBase(base), chat.strip(), embedding.strip(), key.strip());
    }

    Configuration configuration() {
        return configuration;
    }

    void apply(Configuration next) {
        configuration = next;
    }

    public boolean enabled() {
        return configuration.enabled();
    }

    public boolean embeddingsEnabled() {
        var settings = configuration;
        return settings.enabled() && !settings.embedding().isEmpty();
    }

    public String mode() {
        return enabled() ? "model" : "demo";
    }

    public String embeddingSpace() {
        var settings = configuration;
        return settings.base() + "/" + settings.embedding();
    }

    public String complete(String instruction, String data) {
        var settings = configuration;
        var request = Map.of("model", settings.chat(), "messages", messages(instruction, data));
        String content = post(settings, "/chat/completions", request)
            .path("choices").path(0).path("message").path("content").asText("").strip();
        if (content.isEmpty()) {
            throw invalidResponse();
        }
        return content;
    }

    void verify(Configuration settings) {
        JsonNode testChat = post(settings, "/chat/completions", Map.of("model", settings.chat(),
            "messages", messages("只回复 OK。", "检测服务是否可用。")));
        if (testChat.path("choices").path(0).path("message").path("content").asText("").isBlank()) {
            throw invalidResponse();
        }
        if (!settings.embedding().isEmpty()) {
            JsonNode testVector = post(settings, "/embeddings",
                Map.of("model", settings.embedding(), "input", List.of("连通性测试")));
            if (!testVector.path("data").path(0).path("embedding").isArray()) throw invalidResponse();
        }
    }

    public String stream(String instruction, String data, Consumer<String> onDelta) {
        var settings = configuration;
        var payload = Map.of("model", settings.chat(), "messages", messages(instruction, data), "stream", true);
        try {
            var response = client.send(request(settings, "/chat/completions", payload), HttpResponse.BodyHandlers.ofLines());
            try (var lines = response.body()) {
                checkStatus(response.statusCode());
                var iterator = lines.iterator();
                var content = new StringBuilder();
                while (!Thread.currentThread().isInterrupted() && iterator.hasNext()) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String event = line.substring(5).strip();
                    if ("[DONE]".equals(event)) {
                        break;
                    }
                    if (event.isEmpty()) {
                        continue;
                    }
                    JsonNode item = json.readTree(event);
                    if (item == null || item.hasNonNull("error")) {
                        throw invalidResponse();
                    }
                    String delta = item.path("choices").path(0).path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        onDelta.accept(delta);
                        content.append(delta);
                    }
                }
                if (Thread.currentThread().isInterrupted() || content.toString().isBlank()) {
                    throw invalidResponse();
                }
                return content.toString();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw interrupted();
        } catch (IOException | IllegalArgumentException ex) {
            throw connectionFailed();
        }
    }

    public List<double[]> embed(List<String> texts) {
        var settings = configuration;
        var results = new ArrayList<double[]>();
        for (int start = 0; start < texts.size(); start += 8) {
            List<String> batch = texts.subList(start, Math.min(start + 8, texts.size()));
            JsonNode data = post(settings, "/embeddings", Map.of("model", settings.embedding(), "input", batch)).path("data");
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

    static String cleanBase(String value) {
        return value.strip().replaceAll("/+$", "");
    }

    static URI endpoint(String base, String route) {
        URI uri = URI.create(base + route);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
            || uri.getFragment() != null || !List.of("https", "http").contains(uri.getScheme())) {
            throw new IllegalArgumentException("invalid endpoint");
        }
        if ("http".equals(uri.getScheme()) && !List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())) {
            throw new IllegalArgumentException("remote endpoint requires https");
        }
        return uri;
    }

    private List<Map<String, String>> messages(String instruction, String data) {
        return List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", data));
    }

    private HttpRequest request(Configuration settings, String route, Object payload) throws IOException {
        if (!settings.enabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "尚未配置模型连接。");
        }
        return HttpRequest.newBuilder(endpoint(settings.base(), route))
            .timeout(Duration.ofSeconds(45))
            .header("Authorization", "Bearer " + settings.key())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8))
            .build();
    }

    private JsonNode post(Configuration settings, String route, Object payload) {
        try {
            var response = client.send(request(settings, route, payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            checkStatus(response.statusCode());
            JsonNode parsed = json.readTree(response.body());
            if (parsed == null || !parsed.isObject()) {
                throw invalidResponse();
            }
            return parsed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw interrupted();
        } catch (IOException | IllegalArgumentException ex) {
            throw connectionFailed();
        }
    }

    private void checkStatus(int code) {
        if (code != 200) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型服务返回 HTTP " + code + "，请检查配置或稍后重试。");
        }
    }

    private ResponseStatusException connectionFailed() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型连接失败，请检查配置和网络。");
    }

    private ResponseStatusException interrupted() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模型调用已中断，请重试。");
    }

    private ResponseStatusException invalidResponse() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回的数据格式不符合预期。");
    }

    record Configuration(String base, String chat, String embedding, String key) {
        boolean enabled() {
            return !key.isEmpty() && !base.isEmpty() && !chat.isEmpty();
        }
    }
}
