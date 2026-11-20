package com.interviewlab.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:interview-history-tests;DB_CLOSE_DELAY=-1",
    "workbench.model.api-key=",
    "workbench.model.base-url=",
    "workbench.model.chat="
})
@AutoConfigureMockMvc
class InterviewQuestionHistoryTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetInterviewHistory() {
        jdbc.update("DELETE FROM interview_session");
    }

    @Test
    void excludesOnlyAnsweredMainQuestionsAcrossDifficultyAndPreservesExistingPlans() throws Exception {
        JsonNode first = start(6, "mid", true);
        String answered = question(first);
        JsonNode followUp = answer(first, false);
        assertThat(followUp.path("followUpOf").asInt()).isZero();
        JsonNode skipped = answer(followUp, false);
        String skippedQuestion = question(skipped);
        JsonNode unsubmitted = answer(skipped, true);
        String unsubmittedQuestion = question(unsubmitted);

        jdbc.update("""
            INSERT INTO interview_session (id, role_code, next_index, topic_code)
            VALUES ('historical-no-response', 'qa', 2, 'testing')
            """);
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text, follow_up_of)
            VALUES ('historical-no-response', 0, ?, '旧追问', '', 0),
                   ('historical-no-response', 1, ?, ?, '', NULL)
            """, skippedQuestion, unsubmittedQuestion, "　 \t\n");

        JsonNode next = start(3, "junior", false);
        assertThat(skippedQuestion).startsWith(question(next));
        assertThat(answered).doesNotStartWith(question(next));
        next = answer(next, true);
        assertThat(unsubmittedQuestion).startsWith(question(next));
        assertThat(jdbc.queryForObject("SELECT next_index FROM interview_session WHERE id = ?", Integer.class,
            first.path("id").asText())).isEqualTo(3);
    }

    @Test
    void fillsTwentyQuestionsWithFreshFirstAndOldestReuseWithoutDuplicates() throws Exception {
        JsonNode first = start(20, "junior", false);
        var prior = new ArrayList<String>();
        for (int index = 0; index < 20; index++) {
            prior.add(question(first));
            first = answer(first, false);
        }
        assertThat(prior).doesNotHaveDuplicates();
        assertThat(first.path("completed").asBoolean()).isTrue();
        jdbc.update("""
            INSERT INTO interview_session (id, role_code, next_index, topic_code, created_at)
            VALUES ('latest-revisit', 'qa', 1, 'testing', ?)
            """, OffsetDateTime.now().plusMinutes(1));
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text)
            VALUES ('latest-revisit', 0, ?, '再次验证', '')
            """, prior.getFirst());

        JsonNode next = start(20, "junior", false);
        assertThat(prior).doesNotContain(question(next));
        next = answer(next, true);
        assertThat(question(next)).isEqualTo(prior.get(1));
        var reused = new ArrayList<String>();
        for (int index = 1; index < 20; index++) {
            reused.add(question(next));
            next = answer(next, true);
        }
        assertThat(reused).doesNotHaveDuplicates().doesNotContain(prior.getFirst());
        assertThat(next.path("completed").asBoolean()).isTrue();
    }

    @Test
    void readsLegacyAnswerWithoutAQuestionPlanAndKeepsScopesSeparate() throws Exception {
        JsonNode original = start(3, "junior", false);
        jdbc.update("""
            INSERT INTO interview_session (id, role_code, next_index, topic_code)
            VALUES ('legacy-answered', 'qa', 1, 'testing')
            """);
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text)
            VALUES ('legacy-answered', 0, ?, '已提交旧答案', '')
            """, question(original));

        JsonNode unrelated = start("qa", "general", 3, "junior", false);
        assertThat(question(unrelated)).isEqualTo(question(original));
        JsonNode next = start(3, "junior", false);
        assertThat(question(next)).isNotEqualTo(question(original));
    }

    @Test
    void usesTheExactResumeWhenLinkedAndAllMatchingHistoriesWhenUnlinked() throws Exception {
        String sharedQuestion = question(start(3, "junior", false));
        jdbc.update("""
            INSERT INTO resume_review (id, filename, role_label, body, fingerprint, task_state)
            VALUES ('first-source', '示例一.txt', '测试开发', '接口测试', 'first-source', 'COMPLETED'),
                   ('another-source', '示例二.txt', '测试开发', '接口测试', 'another-source', 'COMPLETED')
            """);
        jdbc.update("""
            INSERT INTO interview_session (id, role_code, next_index, topic_code, resume_id)
            VALUES ('linked-history', 'qa', 1, 'testing', 'first-source')
            """);
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text)
            VALUES ('linked-history', 0, ?, '已用测试数据验证', '')
            """, sharedQuestion);

        assertThat(question(start(3, "junior", false))).isNotEqualTo(sharedQuestion);
        assertThat(question(answer(start("qa", "testing", 3, "junior", false, "first-source"), true)))
            .isNotEqualTo(sharedQuestion);
        assertThat(question(answer(start("qa", "testing", 3, "junior", false, "another-source"), true)))
            .isEqualTo(sharedQuestion);
    }

    private JsonNode start(int count, String difficulty, boolean followUps) throws Exception {
        return start("qa", "testing", count, difficulty, followUps);
    }

    private JsonNode start(String role, String topic, int count, String difficulty, boolean followUps) throws Exception {
        return start(role, topic, count, difficulty, followUps, null);
    }

    private JsonNode start(String role, String topic, int count, String difficulty, boolean followUps, String resumeId)
            throws Exception {
        var settings = json.createObjectNode().put("role", role).put("topic", topic).put("questionCount", count)
            .put("difficulty", difficulty).put("followUps", followUps);
        if (resumeId != null) settings.put("resumeId", resumeId);
        var response = http.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON).content(settings.toString()))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    private JsonNode answer(JsonNode interview, boolean skip) throws Exception {
        var body = json.createObjectNode().put("index", interview.path("index").asInt());
        if (skip) body.put("action", "skip");
        else body.put("answer", "保留可复查的测试记录。");
        var response = http.perform(post("/api/interviews/{id}/{operation}", interview.path("id").asText(),
                skip ? "state" : "answers").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
            .andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray());
    }

    private String question(JsonNode interview) {
        return interview.path("question").asText();
    }
}