package com.interviewlab.workbench;

import com.interviewlab.workbench.ai.ModelGateway;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final ModelGateway model;

    public StatusController(ModelGateway model) {
        this.model = model;
    }

    @GetMapping("/api/status")
    Map<String, String> status() {
        return Map.of("mode", model.mode(), "retrieval", model.embeddingsEnabled() ? "hybrid" : "lexical");
    }
}
