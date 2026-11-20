package com.interviewlab.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:workspace_parity;DB_CLOSE_DELAY=-1",
    "workbench.model.api-key=", "workbench.model.base-url=", "workbench.model.chat=", "workbench.model.embedding="
})
@AutoConfigureMockMvc
class WorkspaceParityTest {
    private static final Path KEY = Path.of(System.getProperty("java.io.tmpdir"),
        "interview-workbench-test-key-" + UUID.randomUUID());

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void privateStoreLocation(DynamicPropertyRegistry properties) {
        properties.add("workbench.model.key-file", KEY::toString);
    }

    @Test
    void scheduleEditsPreserveLocalWallClock() throws Exception {
        String initial = """
            {"company":"样例企业","position":"Java 后端","time":"2026-12-10T14:30:00","type":"ONLINE",
             "location":"会议链接另行提供","notes":"携带简历","status":"PENDING"}
            """;
        var created = http.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(initial))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String id = json.readTree(created.getContentAsByteArray()).path("id").asText();
        http.perform(put("/api/schedules/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(initial.replace("PENDING", "COMPLETED")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        http.perform(get("/api/schedules")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].time", startsWith("2026-12-10T14:30")));
        http.perform(delete("/api/schedules/{id}", id)).andExpect(status().isNoContent());
    }

    @Test
    void invitationPreviewAndRescheduleKeepMeetingDetails() throws Exception {
        String invitation = json.writeValueAsString(java.util.Map.of(
            "text", """
                公司：样例科技
                岗位：Java 后端
                面试时间：2026年12月10日下午2:30
                面试形式：腾讯会议
                会议链接：https://meeting.tencent.com/dm/example
                面试官：张老师
                第 2 轮
                """,
            "referenceDate", "2026-11-20", "useModel", false));
        http.perform(post("/api/schedules/parse").contentType(MediaType.APPLICATION_JSON).content(invitation))
            .andExpect(status().isOk()).andExpect(jsonPath("$.company").value("样例科技"))
            .andExpect(jsonPath("$.position").value("Java 后端"))
            .andExpect(jsonPath("$.time", startsWith("2026-12-10T14:30")))
            .andExpect(jsonPath("$.type").value("ONLINE"))
            .andExpect(jsonPath("$.meetingUrl").value("https://meeting.tencent.com/dm/example"))
            .andExpect(jsonPath("$.interviewer").value("张老师"))
            .andExpect(jsonPath("$.round").value(2))
            .andExpect(jsonPath("$.missing").isEmpty());

        String data = """
            {"company":"样例科技","position":"Java 后端","time":"2035-12-10T14:30","type":"ONLINE",
             "status":"PENDING","meetingUrl":"https://meeting.tencent.com/dm/example","interviewer":"张老师",
             "round":2,"reminderMinutes":15}
            """;
        var response = http.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content(data))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String id = json.readTree(response.getContentAsByteArray()).path("id").asText();
        try {
            http.perform(patch("/api/schedules/{id}/time", id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"time\":\"2035-12-11T14:30:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.time", startsWith("2035-12-11T14:30")))
                .andExpect(jsonPath("$.status").value("RESCHEDULED"))
                .andExpect(jsonPath("$.meetingUrl").value("https://meeting.tencent.com/dm/example"))
                .andExpect(jsonPath("$.reminderMinutes").value(15));
            http.perform(patch("/api/schedules/{id}/time", id).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"time\":\"2024-12-11T14:30:00\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        } finally {
            http.perform(delete("/api/schedules/{id}", id)).andExpect(status().isNoContent());
        }
    }

    @Test
    void savesMaskedSettingsAndRetainsKeyOnlyForSameEndpoint() throws Exception {
        String sampleKey = "test-key-for-workspace";
        String initial = """
            {"baseUrl":"https://example.test/v1","chatModel":"chat-test","embeddingModel":"", "apiKey":"test-key-for-workspace"}
            """;
        try {
            var saved = http.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON).content(initial))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasKey").value(true)).andReturn().getResponse();
            assertThat(saved.getContentAsString()).doesNotContain(sampleKey);
            assertThat(jdbc.queryForObject("select encrypted_key from model_settings where id=1", String.class))
                .isNotBlank().doesNotContain(sampleKey);
            http.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
                    .content(initial.replace(sampleKey, "").replace("chat-test", "chat-two")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasKey").value(true));
            http.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
                    .content(initial.replace(sampleKey, "").replace("example.test", "other.test")))
                .andExpect(status().isBadRequest());
        } finally {
            http.perform(put("/api/settings/model").contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"\",\"chatModel\":\"\",\"embeddingModel\":\"\",\"apiKey\":\"\"}"));
            Files.deleteIfExists(KEY);
        }
    }

    @Test
    void asyncResumeSubmissionDeduplicatesAndReturnsCompletedFeedback() throws Exception {
        String sample = """
            {"title":"功能验证","role":"Java 后端","text":"编写订单接口并验证重复提交。"}
            """;
        var created = http.perform(post("/api/resume-reviews").contentType(MediaType.APPLICATION_JSON).content(sample))
            .andExpect(status().isAccepted()).andReturn().getResponse();
        String id = json.readTree(created.getContentAsByteArray()).path("id").asText();
        http.perform(post("/api/resume-reviews").contentType(MediaType.APPLICATION_JSON).content(sample))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(id));
        for (int attempt = 0; attempt < 40; attempt++) {
            var response = http.perform(get("/api/resume-reviews/{id}", id)).andReturn().getResponse();
            var detail = json.readTree(response.getContentAsByteArray());
            if ("COMPLETED".equals(detail.path("status").asText())) {
                assertThat(detail.path("feedback").asText()).contains("目标岗位");
                return;
            }
            assertThat(detail.path("status").asText()).isNotEqualTo("FAILED");
            Thread.sleep(160);
        }
        throw new AssertionError("Resume task did not complete in the test deadline");
    }
}
