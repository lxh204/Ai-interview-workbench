package com.interviewlab.workbench.knowledge;

import com.interviewlab.workbench.knowledge.KnowledgeBank.Draft;
import com.interviewlab.workbench.knowledge.KnowledgeBank.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/documents/{documentId}/questions")
public class KnowledgeBankController {
    private final KnowledgeBank bank;

    public KnowledgeBankController(KnowledgeBank bank) {
        this.bank = bank;
    }

    @GetMapping
    List<Item> list(@PathVariable String documentId) {
        return bank.list(documentId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Item add(@PathVariable String documentId, @Valid @RequestBody QuestionForm input) {
        return bank.add(documentId, input.draft());
    }

    @PutMapping("/{id}")
    Item edit(@PathVariable String documentId, @PathVariable String id, @Valid @RequestBody QuestionForm input) {
        return bank.edit(documentId, id, input.draft());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String documentId, @PathVariable String id) {
        bank.remove(documentId, id);
    }

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    List<Item> generate(@PathVariable String documentId, @Valid @RequestBody Generation input) {
        return bank.generate(documentId, input.count(), input.difficulty());
    }

    record QuestionForm(
        @NotBlank(message = "请输入题干。") @Size(max = 600, message = "题干最多 600 字符。") String question,
        @NotBlank(message = "请输入参考答案。") @Size(max = 5000, message = "参考答案最多 5000 字符。") String referenceAnswer,
        @NotBlank(message = "请输入题目方向。") @Size(max = 100, message = "题目方向最多 100 字符。") String category,
        @NotNull(message = "请选择难度。") @Pattern(regexp = "基础|进阶|深入", message = "请选择有效难度。") String difficulty,
        @NotNull(message = "请选择状态。") @Pattern(regexp = "DRAFT|ACTIVE|ARCHIVED", message = "请选择有效状态。") String status
    ) {
        Draft draft() {
            return new Draft(question, referenceAnswer, category, difficulty, status);
        }
    }

    record Generation(
        @NotNull(message = "请选择题量。") @Min(value = 1, message = "至少生成 1 道题。")
        @Max(value = 10, message = "每次最多生成 10 道题。") Integer count,
        @NotNull(message = "请选择难度。") @Pattern(regexp = "基础|进阶|深入", message = "请选择有效难度。") String difficulty
    ) {}
}
