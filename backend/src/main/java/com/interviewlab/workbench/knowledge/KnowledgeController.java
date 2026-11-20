package com.interviewlab.workbench.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeDocuments documents;
    private final KnowledgeAnswers answers;

    public KnowledgeController(KnowledgeDocuments documents, KnowledgeAnswers answers) {
        this.documents = documents;
        this.answers = answers;
    }

    @GetMapping("/documents")
    List<KnowledgeDocuments.Document> list() {
        return documents.list();
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocuments.Document add(@Valid @RequestBody NewDocument input) {
        return documents.add(input.title(), input.text(), input.category(), null, null);
    }

    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocuments.Document upload(@RequestParam MultipartFile file,
                                       @RequestParam(defaultValue = "") String title,
                                       @RequestParam(defaultValue = "") String category) {
        return documents.upload(file, title, category);
    }

    @PatchMapping("/documents/{id}")
    KnowledgeDocuments.Document categorize(@PathVariable String id, @Valid @RequestBody Category input) {
        return documents.categorize(id, input.category());
    }

    @PostMapping("/documents/{id}/index")
    KnowledgeDocuments.Document reindex(@PathVariable String id) {
        return documents.reindex(id);
    }

    @GetMapping("/documents/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable String id) {
        var file = documents.download(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8).build().toString())
            .body(file.content());
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String id) {
        documents.remove(id);
    }

    @PostMapping("/ask")
    KnowledgeAnswers.Result ask(@Valid @RequestBody Question request) {
        return answers.ask(request.question().strip(), List.of(), List.of(), null);
    }

    record NewDocument(
        @NotBlank(message = "请输入文档标题。") @Size(max = 100, message = "文档标题最多 100 字符。") String title,
        @NotBlank(message = "请输入文档内容。") @Size(max = 60_000, message = "单份文档最多 60000 字符。") String text,
        @Size(max = 100, message = "分类最多 100 字符。") String category) {}

    record Category(@Size(max = 100, message = "分类最多 100 字符。") String category) {}

    record Question(@NotBlank(message = "请输入问题。") @Size(max = 500, message = "问题最多 500 字符。") String question) {}
}
