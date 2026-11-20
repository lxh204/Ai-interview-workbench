package com.interviewlab.workbench;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:flows;DB_CLOSE_DELAY=-1",
    "workbench.model.api-key=",
    "workbench.model.base-url=",
    "workbench.model.chat=",
    "workbench.model.embedding="
})
@AutoConfigureMockMvc
class WorkbenchFlowsTest {
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;

    @Test
    void resumeProvidesLocalFeedbackAndValidatesBlankInputs() throws Exception {
        http.perform(post("/api/resumes/analyze").contentType(MediaType.APPLICATION_JSON).content("""
            {"role":"Java 后端","text":"开发了库存服务和订单接口"}
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("demo"))
            .andExpect(jsonPath("$.feedback", containsString("真实结果")));
        http.perform(post("/api/resumes/analyze").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Java\",\"text\":\"  \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @Test
    void importsUtf8ResumeText() throws Exception {
        var file = new MockMultipartFile("file", "resume.txt", "text/plain",
            "实现并验证了 Java 查询接口。".getBytes(StandardCharsets.UTF_8));
        http.perform(multipart("/api/resumes/upload").file(file).param("role", "Java 后端"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sampleText").value("实现并验证了 Java 查询接口。"));
    }

    @Test
    void extractsTextFromPdf() throws Exception {
        var buffer = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(48, 720);
                stream.showText("Java REST API development.");
                stream.endText();
            }
            pdf.save(buffer);
        }
        var upload = new MockMultipartFile("file", "sample.pdf", "application/pdf", buffer.toByteArray());
        http.perform(multipart("/api/resumes/upload").file(upload).param("role", "Java 后端"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sampleText").value("Java REST API development."));
    }

    @Test
    void knowledgeAnswersContainTheStoredPassageAndDiscardDeletedDocuments() throws Exception {
        var created = http.perform(post("/api/knowledge/documents").contentType(MediaType.APPLICATION_JSON).content("""
            {"title":"排班手册","text":"设备巡检每周三执行，巡检员在下午三点登记设备温度。"}
            """))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.chunkCount").value(1))
            .andReturn().getResponse();
        String id = json.readTree(created.getContentAsByteArray()).path("id").asText();
        String question = "{\"question\":\"设备巡检什么时候执行？\"}";
        http.perform(post("/api/knowledge/ask").contentType(MediaType.APPLICATION_JSON).content(question))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sources[0].documentId").value(id))
            .andExpect(jsonPath("$.sources[0].text", containsString("每周三")));
        http.perform(delete("/api/knowledge/documents/{id}", id)).andExpect(status().isNoContent());
        http.perform(post("/api/knowledge/ask").contentType(MediaType.APPLICATION_JSON).content(question))
            .andExpect(status().isOk()).andExpect(jsonPath("$.sources").isEmpty());
    }

    @Test
    void interviewRejectsStaleAnswerWithoutMovingProgressTwice() throws Exception {
        var created = http.perform(post("/api/interviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"java\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.index").value(0))
            .andReturn().getResponse();
        String id = json.readTree(created.getContentAsByteArray()).path("id").asText();
        String answer = "{\"index\":0,\"answer\":\"在事务内做库存条件更新，并核对更新行数。\"}";
        http.perform(post("/api/interviews/{id}/answers", id)
                .contentType(MediaType.APPLICATION_JSON).content(answer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.index").value(1));
        http.perform(post("/api/interviews/{id}/answers", id)
                .contentType(MediaType.APPLICATION_JSON).content(answer))
            .andExpect(status().isConflict());
        http.perform(get("/api/interviews/{id}", id))
            .andExpect(status().isOk()).andExpect(jsonPath("$.index").value(1))
            .andExpect(jsonPath("$.answers.length()").value(1));
    }
}
