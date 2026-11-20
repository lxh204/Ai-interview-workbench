package com.interviewlab.workbench.interview;

import java.time.OffsetDateTime;
import java.util.List;

record InterviewResponse(
    String id, String role, int index, int total, String question, boolean completed, List<Answer> answers,
    String topic, String difficulty, String kind, boolean paused, OffsetDateTime createdAt, OffsetDateTime completedAt,
    int skippedCount, String resumeId, String resumeName, String providerId, Integer followUpOf, boolean bookmarked, String draft
) {
    record Summary(String id, String role, int index, int total, boolean completed, String topic, String difficulty,
                   String kind, boolean paused, OffsetDateTime createdAt, OffsetDateTime completedAt,
                   int skippedCount, String resumeId, String resumeName, String providerId) {}

    record Answer(String question, String answer, String feedback, Integer score, boolean skipped, boolean bookmarked,
                  Integer followUpOf) {}
}
