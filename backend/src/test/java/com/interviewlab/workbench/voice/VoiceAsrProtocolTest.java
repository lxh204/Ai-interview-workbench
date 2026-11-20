package com.interviewlab.workbench.voice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class VoiceAsrProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final VoiceAsrProtocol protocol = new VoiceAsrProtocol(json);

    @Test
    void requestsDuplexMonoPcmAtTheCaptureSampleRate() throws Exception {
        var request = json.readTree(protocol.start("task-123"));
        assertThat(request.at("/header/action").textValue()).isEqualTo("run-task");
        assertThat(request.at("/header/task_id").textValue()).isEqualTo("task-123");
        assertThat(request.at("/header/streaming").textValue()).isEqualTo("duplex");
        assertThat(request.at("/payload/model").textValue()).isEqualTo("fun-asr-realtime");
        assertThat(request.at("/payload/parameters/format").textValue()).isEqualTo("pcm");
        assertThat(request.at("/payload/parameters/sample_rate").intValue()).isEqualTo(16_000);
        assertThat(json.readTree(protocol.finish("task-123")).at("/header/action").textValue()).isEqualTo("finish-task");
    }

    @Test
    void filtersOtherTasksAndHeartbeatPacketsButEmitsInterimAndFinalText() throws Exception {
        String event = """
            {"header":{"task_id":"task-123","event":"result-generated"},
             "payload":{"output":{"sentence":{"sentence_id":1,"text":"线程安全","sentence_end":false}}}}
            """;
        assertThat(protocol.read(event, "old-task")).isNull();
        assertThat(protocol.read(event.replace("\"sentence_end\":false", "\"heartbeat\":true"), "task-123")).isNull();
        assertThat(protocol.read(event, "task-123")).isEqualTo(
            new VoiceAsrProtocol.Event("transcript", "线程安全", false));
        assertThat(protocol.read(event.replace("false", "true"), "task-123")).isEqualTo(
            new VoiceAsrProtocol.Event("transcript", "线程安全", true));
    }
}
