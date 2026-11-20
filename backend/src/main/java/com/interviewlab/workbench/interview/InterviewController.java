package com.interviewlab.workbench.interview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/interviews")
class InterviewController {
    private final InterviewService interviews;
    private final InterviewReport reports;

    InterviewController(InterviewService interviews, InterviewReport reports) {
        this.interviews = interviews;
        this.reports = reports;
    }

    @GetMapping
    List<InterviewResponse.Summary> list() {
        return interviews.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InterviewResponse create(@Valid @RequestBody CreateRequest request) {
        return interviews.create(InterviewSettings.create(request.role(), request.topic(), request.difficulty(),
            request.questionCount(), request.kind(), request.jdText()));
    }

    @GetMapping("/{id}")
    InterviewResponse get(@PathVariable String id) {
        return interviews.get(id);
    }

    @GetMapping("/{id}/report.pdf")
    ResponseEntity<byte[]> report(@PathVariable String id) {
        InterviewResponse interview = interviews.get(id);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("interview-" + interview.id() + ".pdf").build().toString())
            .body(reports.render(interview));
    }

    @PostMapping("/{id}/answers")
    InterviewResponse answer(@PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return interviews.answer(id, request.index(), request.answer());
    }

    @PostMapping("/{id}/state")
    InterviewResponse state(@PathVariable String id, @Valid @RequestBody StateRequest request) {
        return interviews.changeState(id, request.index(), request.action());
    }

    record CreateRequest(
        @NotBlank(message = "请选择面试方向。")
        @Pattern(regexp = "java|ai|frontend|python|qa", message = "请选择可用的面试方向。")
        String role,
        @Pattern(regexp = "general|spring|storage|rag|model|algorithm|frontend|python|system|testing|jd", message = "请选择可用的面试主题。")
        String topic,
        @Pattern(regexp = "junior|mid|senior", message = "请选择可用的面试难度。")
        String difficulty,
        @Min(value = 3, message = "每次面试至少 3 题。")
        @Max(value = 6, message = "每次面试至多 6 题。")
        Integer questionCount,
        @Pattern(regexp = "text|voice", message = "请选择文字或语音练习。")
        String kind,
        @Size(max = 3000, message = "岗位描述最长为 3000 字符。")
        String jdText
    ) {}

    record AnswerRequest(
        @NotNull(message = "缺少题目序号。")
        @PositiveOrZero(message = "题目序号不能小于 0。")
        Integer index,
        @NotBlank(message = "请输入你的回答。")
        @Size(max = 8000, message = "回答最长为 8000 字符。")
        String answer
    ) {}

    record StateRequest(
        @NotNull(message = "缺少题目序号。")
        @PositiveOrZero(message = "题目序号不能小于 0。")
        Integer index,
        @NotNull(message = "请选择面试操作。")
        @Pattern(regexp = "pause|resume|finish", message = "请选择可用的面试操作。")
        String action
    ) {}
}
