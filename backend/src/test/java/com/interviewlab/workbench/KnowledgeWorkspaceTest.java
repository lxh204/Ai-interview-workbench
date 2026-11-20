package com.interviewlab.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    @Test
    void countsDocxUsageAndEnforcesFollowUpCapacity() throws Exception {
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("""
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                <w:body><w:p><w:r><w:t>项目服务重试间隔为十五秒。</w:t></w:r></w:p></w:body>
                </w:document>
                """.getBytes(StandardCharsets.UTF_8));
        }
        byte[] file = archive.toByteArray();
        var upload = new MockMultipartFile("file", "重试手册.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", file);
        var document = http.perform(multipart("/api/knowledge/documents/upload").file(upload))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sizeBytes").value(file.length))
            .andReturn().getResponse();
        String documentId = id(document.getContentAsByteArray());
        http.perform(get("/api/knowledge/documents/{id}/download", documentId)).andExpect(status().isOk())
            .andExpect(content().bytes(file));
        http.perform(get("/api/knowledge/documents/{id}/questions", documentId)).andExpect(status().isOk());
        var session = http.perform(post("/api/knowledge/conversations").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("documentIds", List.of(documentId)))))
            .andExpect(status().isCreated()).andReturn().getResponse();
        http.perform(post("/api/knowledge/conversations/{id}/messages", id(session.getContentAsByteArray()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"服务重试间隔是多久？\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.sources[0].text").value("项目服务重试间隔为十五秒。"));
        http.perform(get("/api/knowledge/documents")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].questionCount", contains(1)))
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].accessCount", contains(2)));

        var generation = http.perform(post("/api/knowledge/documents/{id}/questions/generate", documentId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"count\":2,\"difficulty\":\"基础\",\"followUpCount\":1}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$[0].followUps.length()").value(1))
            .andReturn().getResponse();
        var ids = new java.util.ArrayList<String>();
        for (var item : json.readTree(generation.getContentAsByteArray())) {
            ids.add(item.path("id").asText());
        }
        http.perform(post("/api/knowledge/documents/{id}/questions/batch-status", documentId)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("ids", ids, "status", "ACTIVE"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[1].status").value("ACTIVE"));
        http.perform(post("/api/knowledge/practices").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("documentId", documentId, "mainCount", 2, "followUpCount", 2))))
            .andExpect(status().isConflict());
        var practice = http.perform(post("/api/knowledge/practices").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("documentId", documentId, "mainCount", 2, "followUpCount", 1))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(4))
            .andExpect(jsonPath("$.questions[1].followUpIndex").value(1))
            .andReturn().getResponse();
        http.perform(get("/api/knowledge/question-banks")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].active", contains(2)))
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].draft", contains(0)))
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].practices", contains(1)))
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].completed", contains(0)))
            .andExpect(jsonPath("$[?(@.id == '" + documentId + "')].lastPractice").isNotEmpty());
        http.perform(delete("/api/knowledge/documents/{id}", documentId)).andExpect(status().isNoContent());
        http.perform(get("/api/knowledge/practices/{id}", id(practice.getContentAsByteArray())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.questions[3].followUpIndex").value(1));
    }

    private String id(byte[] response) throws Exception {
        return json.readTree(response).path("id").asText();
    }
}
