package com.interviewlab.workbench.resume;

import com.interviewlab.workbench.report.ReportPdf;
import com.interviewlab.workbench.resume.ResumeReviews.Review;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/resume-reviews")
public class ResumeReviewsController {
    private final ResumeReviews reviews;
    private final ReportPdf pdf;

    public ResumeReviewsController(ResumeReviews reviews, ReportPdf pdf) {
        this.reviews = reviews;
        this.pdf = pdf;
    }

    @GetMapping
    List<Review> list() {
        return reviews.list();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Review paste(@Valid @RequestBody TextReview request) {
        var name = request.title().strip() + ".txt";
        return reviews.enqueue(name, request.role().strip(), ResumeFiles.clean(request.text()), null);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    Review upload(@RequestParam MultipartFile file, @RequestParam String role) {
        if (role.isBlank() || role.length() > 100 || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写目标岗位并选择简历。");
        }
        String filename = cleanFilename(file.getOriginalFilename());
        try {
            byte[] bytes = file.getBytes();
            return reviews.enqueue(filename, role.strip(), ResumeFiles.read(filename, bytes), bytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传文件。");
        }
    }

    @GetMapping("/{id}")
    Review detail(@PathVariable String id) {
        return reviews.detail(id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void retry(@PathVariable String id) {
        reviews.retry(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) {
        reviews.delete(id);
    }

    @GetMapping("/{id}/original")
    ResponseEntity<byte[]> original(@PathVariable String id) {
        Review review = reviews.detail(id);
        return file(review.filename(), MediaType.APPLICATION_OCTET_STREAM, reviews.originalFile(id));
    }

    @GetMapping("/{id}/export")
    ResponseEntity<byte[]> report(@PathVariable String id) {
        Review review = reviews.detail(id);
        if (!"COMPLETED".equals(review.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "分析完成后可导出报告。");
        }
        byte[] output = pdf.render("简历分析报告", "文件：" + review.filename() + "\n岗位：" + review.role() + "\n\n" + review.feedback());
        return file("简历分析报告.pdf", MediaType.APPLICATION_PDF, output);
    }

    private ResponseEntity<byte[]> file(String name, MediaType type, byte[] bytes) {
        return ResponseEntity.ok().contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private String cleanFilename(String filename) {
        String value = filename == null ? "" : filename.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).strip();
        if (value.isEmpty() || value.length() > 180 || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件名无效或超过 180 字符。");
        }
        return value;
    }

    record TextReview(@NotBlank @Size(max = 150) String title, @NotBlank @Size(max = 100) String role,
                      @NotBlank @Size(max = ResumeFiles.MAX_TEXT) String text) {}
}
