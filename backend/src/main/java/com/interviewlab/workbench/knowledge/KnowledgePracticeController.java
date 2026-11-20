package com.interviewlab.workbench.knowledge;

import com.interviewlab.workbench.knowledge.KnowledgePractices.Practice;
import com.interviewlab.workbench.knowledge.KnowledgePractices.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/practices")
public class KnowledgePracticeController {
    private final KnowledgePractices practices;

    public KnowledgePracticeController(KnowledgePractices practices) {
        this.practices = practices;
    }

    @GetMapping
    List<Summary> list() {
        return practices.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Practice start(@Valid @RequestBody NewPractice input) {
        return practices.start(input.documentId(), input.questionIds(), input.mainCount(),
            input.followUpCount() == null ? 0 : input.followUpCount(), KnowledgeDocuments.clean(input.category()),
            KnowledgeDocuments.clean(input.difficulty()));
    }

    @GetMapping("/{id}")
    Practice get(@PathVariable String id) {
        return practices.get(id);
    }

    @PostMapping("/{id}/answers")
    Practice answer(@PathVariable String id, @Valid @RequestBody NewAnswer input) {
        return practices.answer(id, input.index(), input.answer().strip());
    }

    record NewPractice(@NotBlank(message = "请选择资料。") String documentId,
        @Size(min = 1, max = 100, message = "请选择 1 至 100 道候选题。")
        List<@NotBlank(message = "存在无效题目。") String> questionIds,
        @Min(value = 1, message = "至少需要 1 道主问题。") @Max(value = 20, message = "每次最多 20 道主问题。") Integer mainCount,
        @Min(value = 0, message = "追问数不能小于 0。") @Max(value = 5, message = "每道主问题最多 5 个追问。") Integer followUpCount,
        @Size(max = 100, message = "方向最多 100 字符。") String category,
        @Pattern(regexp = "|基础|进阶|深入", message = "请选择有效难度。") String difficulty) {}

    record NewAnswer(@NotNull(message = "题号不能为空。") @PositiveOrZero(message = "题号无效。") Integer index,
        @NotBlank(message = "请输入这道题的回答。") @Size(max = 5000, message = "回答最多 5000 字符。") String answer) {}
}
