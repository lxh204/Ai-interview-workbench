package com.interviewlab.workbench.interview;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

record InterviewSettings(String role, String topic, String difficulty, int questionCount, String kind,
                         String jdText, String resumeId, String providerId, boolean followUps) {
    private static final Map<String, List<String>> TOPICS = Map.of(
        "java", List.of("spring", "storage", "algorithm", "system", "testing", "ali", "byte", "tencent"),
        "ai", List.of("agent", "rag", "model", "algorithm", "system", "testing"),
        "frontend", List.of("frontend", "algorithm", "testing", "system"),
        "python", List.of("python", "storage", "algorithm", "system", "testing"),
        "qa", List.of("testing", "storage", "system", "algorithm")
    );

    static InterviewSettings create(String role, String topic, String difficulty, Integer count, String kind,
                                    String jdText, String resumeId, String providerId, Boolean followUps) {
        String selectedTopic = topic == null ? "general" : topic;
        List<String> available = topics(role);
        if (!selectedTopic.equals("general") && !selectedTopic.equals("jd") && !available.contains(selectedTopic)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该主题与选择的面试方向不匹配。");
        }
        String description = jdText == null ? "" : jdText.strip();
        if (selectedTopic.equals("jd") && description.length() < 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入至少 30 个字符的岗位描述。");
        }
        return new InterviewSettings(role, selectedTopic, difficulty == null ? "junior" : difficulty,
            count == null ? 3 : count, kind == null ? "text" : kind, description,
            optional(resumeId), optional(providerId), Boolean.TRUE.equals(followUps));
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static List<String> topics(String role) {
        return TOPICS.getOrDefault(role, List.of());
    }
}
