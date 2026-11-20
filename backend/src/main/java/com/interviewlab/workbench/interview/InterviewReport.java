package com.interviewlab.workbench.interview;

import com.interviewlab.workbench.report.ReportPdf;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class InterviewReport {
    private static final Map<String, String> TOPICS = Map.ofEntries(
        Map.entry("general", "综合面试"), Map.entry("spring", "Spring 实战"), Map.entry("storage", "数据库与缓存"),
        Map.entry("rag", "知识库与 RAG"), Map.entry("model", "模型应用工程"), Map.entry("algorithm", "数据结构与算法"),
        Map.entry("frontend", "前端工程"), Map.entry("python", "Python 后端"), Map.entry("system", "系统设计"),
        Map.entry("testing", "测试开发"), Map.entry("jd", "JD 定制面试"),
        Map.entry("agent", "AI Agent 开发"), Map.entry("ali", "阿里后端"),
        Map.entry("byte", "字节后端"), Map.entry("tencent", "腾讯后端")
    );
    private static final Map<String, String> ROLES = Map.of(
        "java", "Java 后端开发", "ai", "AI 应用开发", "frontend", "前端开发", "python", "Python 后端开发", "qa", "测试开发"
    );
    private static final Map<String, String> LEVELS = Map.of("junior", "校招", "mid", "中级", "senior", "高级");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm XXX");

    private final ReportPdf pdf;

    InterviewReport(ReportPdf pdf) {
        this.pdf = pdf;
    }

    byte[] render(InterviewResponse interview) {
        if (!interview.completed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成面试，再导出报告。");
        }
        var content = new StringBuilder()
            .append(ROLES.get(interview.role()))
            .append(" · ").append(interview.kind().equals("voice") ? "语音面试" : "文字面试")
            .append(" · ").append(TOPICS.get(interview.topic())).append(" · ").append(LEVELS.get(interview.difficulty()))
            .append("\n已作答 ").append(interview.index() - interview.skippedCount()).append(" / ").append(interview.total())
            .append(" 题，跳过 ").append(interview.skippedCount()).append(" 题，未进行 ")
            .append(interview.total() - interview.index()).append(" 题\n")
            .append("开始时间 ").append(formatDate(interview.createdAt())).append("\n")
            .append("完成时间 ").append(formatDate(interview.completedAt())).append("\n\n");
        if (interview.resumeName() != null) {
            content.append("关联简历 ").append(interview.resumeName()).append("\n\n");
        }
        for (int index = 0; index < interview.answers().size(); index++) {
            var answer = interview.answers().get(index);
            content.append("第 ").append(index + 1).append(" 题");
            if (answer.followUpOf() != null) {
                content.append(" · 第 ").append(answer.followUpOf() + 1).append(" 题的追问");
            }
            if (answer.bookmarked()) content.append(" · 已收藏");
            content.append("\n").append(answer.question()).append("\n");
            if (answer.skipped()) {
                content.append("本题已跳过。\n\n");
            } else {
                content.append("回答\n").append(answer.answer()).append("\n")
                    .append("反馈\n").append(answer.feedback()).append("\n\n");
            }
        }
        if (interview.answers().isEmpty()) {
            content.append("本次面试未提交回答。");
        }
        return pdf.render("面试报告", content.toString());
    }

    private String formatDate(OffsetDateTime date) {
        return date == null ? "—" : DATE.format(date);
    }
}
