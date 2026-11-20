package com.interviewlab.workbench.voice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Maps DashScope Fun-ASR WebSocket events to the browser's smaller transcript protocol. */
final class VoiceAsrProtocol {
    private final ObjectMapper json;

    VoiceAsrProtocol(ObjectMapper json) {
        this.json = json;
    }

    String start(String taskId) {
        ObjectNode message = control("run-task", taskId);
        ObjectNode payload = message.withObjectProperty("payload");
        payload.put("task_group", "audio").put("task", "asr").put("function", "recognition")
            .put("model", "fun-asr-realtime");
        payload.putObject("parameters").put("format", "pcm").put("sample_rate", 16_000);
        return message.toString();
    }

    String finish(String taskId) {
        return control("finish-task", taskId).toString();
    }

    Event read(String message, String taskId) throws JsonProcessingException {
        var body = json.readTree(message);
        if (!taskId.equals(body.at("/header/task_id").asText())) {
            return null;
        }
        return switch (body.at("/header/event").asText()) {
            case "task-started" -> new Event("ready", "", false);
            case "task-finished" -> new Event("done", "", false);
            case "task-failed" -> new Event("error", "云端转写服务未能完成识别。", false);
            case "result-generated" -> {
                var sentence = body.at("/payload/output/sentence");
                String text = sentence.path("text").asText("").trim();
                yield sentence.path("heartbeat").asBoolean() || text.isEmpty() ? null
                    : new Event("transcript", text, sentence.path("sentence_end").asBoolean());
            }
            default -> null;
        };
    }

    private ObjectNode control(String action, String taskId) {
        ObjectNode message = json.createObjectNode();
        message.putObject("header").put("action", action).put("task_id", taskId).put("streaming", "duplex");
        message.putObject("payload").putObject("input");
        return message;
    }

    record Event(String type, String text, boolean complete) {}
}
