package com.interviewlab.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:knowledge-workspace;DB_CLOSE_DELAY=-1",
    "workbench.model.api-key=",
    "workbench.model.base-url=",
    "workbench.model.chat=",
    "workbench.model.embedding="
})
@AutoConfigureMockMvc
class KnowledgeWorkspaceTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @Test
    void importedSourceScopesConversationAndRemainsAvailableInPracticeRecords() throws Exception {
        var upload = new MockMultipartFile("file", "库存手册.md", "text/markdown",
            "库存扣减通过带余额条件的原子更新实现，更新影响行数为零时拒绝扣减。".getBytes(StandardCharsets.UTF_8));
        var document = http.perform(multipart("/api/knowledge/documents/upload").file(upload).param("category", "项目"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.category").value("项目"))
            .andReturn().getResponse();
        String documentId = id(document.getContentAsByteArray());
        http.perform(post("/api/knowledge/documents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"另一份手册\",\"text\":\"库存扣减通过写入审计文件实现。\"}"))
            .andExpect(status().isCreated());
        var conversation = http.perform(post("/api/knowledge/conversations").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentIds\":[\"" + documentId + "\"]}"))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String conversationId = id(conversation.getContentAsByteArray());
        http.perform(post("/api/knowledge/conversations/{id}/messages", conversationId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"库存扣减如何实现？\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sources.length()").value(1))
            .andExpect(jsonPath("$.sources[0].documentId").value(documentId));
        http.perform(get("/api/knowledge/conversations/{id}", conversationId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.turns.length()").value(1));

        var question = http.perform(post("/api/knowledge/documents/{id}/questions", documentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"怎样约束库存扣减？\",\"referenceAnswer\":\"条件更新\",\"category\":\"数据库\","
                    + "\"difficulty\":\"基础\",\"status\":\"ACTIVE\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String questionId = id(question.getContentAsByteArray());
        var practice = http.perform(post("/api/knowledge/practices").contentType(MediaType.APPLICATION_JSON)
                .content("{\"documentId\":\"" + documentId + "\",\"questionIds\":[\"" + questionId + "\"]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.completed").value(false))
            .andReturn().getResponse();
        String practiceId = id(practice.getContentAsByteArray());
        http.perform(post("/api/knowledge/practices/{id}/answers", practiceId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"index\":0,\"answer\":\"在更新语句中约束库存余额。\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true));
        http.perform(get("/api/knowledge/practices/{id}", practiceId))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[0].referenceAnswer").value("条件更新"));
    }

    private String id(byte[] response) throws Exception {
        return json.readTree(response).path("id").asText();
    }
}
