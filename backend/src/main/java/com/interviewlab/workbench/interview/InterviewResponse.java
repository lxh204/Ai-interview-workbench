package com.interviewlab.workbench.interview;

import java.util.List;

record InterviewResponse(
    String id,
    String role,
    int index,
    int total,
    String question,
    boolean completed,
    List<Answer> answers
) {
    record Answer(String question, String answer, String feedback, Integer score) {}
}
