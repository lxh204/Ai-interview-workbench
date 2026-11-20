package com.interviewlab.workbench.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class VoiceAsrConfiguration implements WebSocketConfigurer {
    private final VoiceAsrRelay relay;

    VoiceAsrConfiguration(ObjectMapper json, JdbcTemplate database,
                          @Value("${WORKBENCH_ASR_API_KEY:}") String key,
                          @Value("${WORKBENCH_ASR_WORKSPACE:}") String workspace) {
        URI endpoint = workspace.matches("[a-zA-Z0-9-]{1,63}")
            ? URI.create("wss://" + workspace + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference") : null;
        relay = new VoiceAsrRelay(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build(),
            endpoint, key.trim(), json, database);
    }

    boolean enabled() {
        return relay.enabled();
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Spring's default handshake policy accepts browser connections from the same origin only.
        registry.addHandler(relay, "/api/voice/asr");
    }
}

@RestController
class VoiceAsrController {
    private final VoiceAsrConfiguration configuration;

    VoiceAsrController(VoiceAsrConfiguration configuration) {
        this.configuration = configuration;
    }

    @GetMapping("/api/voice/capabilities")
    Map<String, Boolean> capabilities() {
        return Map.of("cloudRecognition", configuration.enabled());
    }
}
