package com.interviewlab.workbench.interview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

    InterviewController(InterviewService interviews) {
        this.interviews = interviews;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InterviewResponse create(@Valid @RequestBody CreateRequest request) {
        return interviews.create(request.role());
    }

    @GetMapping("/{id}")
    InterviewResponse get(@PathVariable String id) {
        return interviews.get(id);
    }

    @PostMapping("/{id}/answers")
    InterviewResponse answer(@PathVariable String id, @Valid @RequestBody AnswerRequest request) {
        return interviews.answer(id, request.index(), request.answer());
    }

    record CreateRequest(
        @NotBlank(message = "请选择面试方向。")
        @Pattern(regexp = "java|ai", message = "请选择 Java 后端或 AI 应用面试。")
        String role
    ) {}

    record AnswerRequest(
        @NotNull(message = "缺少题目序号。")
        @PositiveOrZero(message = "题目序号不能小于 0。")
        Integer index,

        @NotBlank(message = "请输入你的回答。")
        @Size(max = 8000, message = "回答最长为 8000 字符。")
        String answer
    ) {}
}
