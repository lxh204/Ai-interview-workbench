package com.interviewlab.workbench.interview;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class InterviewRepository {
    private final JdbcTemplate jdbc;

    InterviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void create(String id, String role) {
        jdbc.update("INSERT INTO interview_session (id, role_code, next_index) VALUES (?, ?, 0)", id, role);
    }

    Optional<Session> find(String id) {
        return jdbc.query("""
            SELECT s.role_code, s.next_index, a.question_text, a.answer_text, a.feedback_text, a.score
            FROM interview_session s
            LEFT JOIN interview_answer a ON a.session_id = s.id
            WHERE s.id = ?
            ORDER BY a.question_index
            """, rows -> {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String role = rows.getString("role_code");
                int index = rows.getInt("next_index");
                var answers = new ArrayList<InterviewResponse.Answer>();
                do {
                    String question = rows.getString("question_text");
                    if (question != null) {
                        answers.add(new InterviewResponse.Answer(question, rows.getString("answer_text"),
                            rows.getString("feedback_text"), rows.getObject("score", Integer.class)));
                    }
                } while (rows.next());
                return Optional.of(new Session(role, index, List.copyOf(answers)));
            }, id);
    }

    boolean advance(String id, int expectedIndex) {
        return jdbc.update("""
            UPDATE interview_session
            SET next_index = next_index + 1
            WHERE id = ? AND next_index = ?
            """, id, expectedIndex) == 1;
    }

    void insertAnswer(String id, int index, InterviewResponse.Answer answer) {
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text, score)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, index, answer.question(), answer.answer(), answer.feedback(), answer.score());
    }

    record Session(String role, int index, List<InterviewResponse.Answer> answers) {}
}
