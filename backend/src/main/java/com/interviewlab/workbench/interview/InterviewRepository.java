package com.interviewlab.workbench.interview;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

    void create(String id, InterviewSettings settings, List<String> questions) {
        jdbc.update("""
            INSERT INTO interview_session
                (id, role_code, next_index, topic_code, difficulty_code, question_count, kind_code, created_at)
            VALUES (?, ?, 0, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """, id, settings.role(), settings.topic(), settings.difficulty(), settings.questionCount(), settings.kind());

        for (int index = 0; index < questions.size(); index++) {
            jdbc.update("""
                INSERT INTO interview_question (session_id, question_index, question_plan) VALUES (?, ?, ?)
                """, id, index, questions.get(index));
        }
    }

    List<InterviewResponse.Summary> list() {
        return jdbc.query("""
            SELECT * FROM interview_session ORDER BY created_at DESC NULLS LAST, id
            """, (rows, index) -> summary(rows));
    }

    Optional<Session> find(String id) {
        return jdbc.query("""
            SELECT s.*, q.question_plan, a.question_text, a.answer_text, a.feedback_text, a.score
            FROM interview_session s
            LEFT JOIN interview_question q ON q.session_id = s.id AND q.question_index = s.next_index
            LEFT JOIN interview_answer a ON a.session_id = s.id
            WHERE s.id = ?
            ORDER BY a.question_index
            """, rows -> {
                if (!rows.next()) {
                    return Optional.empty();
                }
                var summary = summary(rows);
                String questionPlan = rows.getString("question_plan");
                var answers = new ArrayList<InterviewResponse.Answer>();
                do {
                    String question = rows.getString("question_text");
                    if (question != null) {
                        answers.add(new InterviewResponse.Answer(question, rows.getString("answer_text"),
                            rows.getString("feedback_text"), rows.getObject("score", Integer.class)));
                    }
                } while (rows.next());
                return Optional.of(new Session(summary, questionPlan, List.copyOf(answers)));
            }, id);
    }

    boolean advance(String id, int index) {
        return jdbc.update("""
            UPDATE interview_session SET next_index = next_index + 1,
                completed_at = CASE WHEN next_index + 1 = question_count THEN CURRENT_TIMESTAMP ELSE NULL END
            WHERE id = ? AND next_index = ? AND next_index < question_count
                AND completed_at IS NULL AND paused = FALSE
            """, id, index) == 1;
    }

    boolean setPaused(String id, int index, boolean paused) {
        return jdbc.update("""
            UPDATE interview_session SET paused = ?
            WHERE id = ? AND next_index = ? AND next_index < question_count
                AND completed_at IS NULL AND paused <> ? AND kind_code = 'voice'
            """, paused, id, index, paused) == 1;
    }

    boolean finish(String id, int index) {
        return jdbc.update("""
            UPDATE interview_session SET completed_at = CURRENT_TIMESTAMP, paused = FALSE
            WHERE id = ? AND next_index = ? AND next_index < question_count AND completed_at IS NULL
            """, id, index) == 1;
    }

    void insertAnswer(String id, int index, InterviewResponse.Answer answer) {
        jdbc.update("""
            INSERT INTO interview_answer (session_id, question_index, question_text, answer_text, feedback_text, score)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, index, answer.question(), answer.answer(), answer.feedback(), answer.score());
    }

    private static InterviewResponse.Summary summary(ResultSet rows) throws SQLException {
        int index = rows.getInt("next_index");
        int total = rows.getInt("question_count");
        OffsetDateTime completedAt = rows.getObject("completed_at", OffsetDateTime.class);
        return new InterviewResponse.Summary(rows.getString("id"), rows.getString("role_code"), index, total,
            completedAt != null || index >= total, rows.getString("topic_code"), rows.getString("difficulty_code"),
            rows.getString("kind_code"), rows.getBoolean("paused"),
            rows.getObject("created_at", OffsetDateTime.class), completedAt);
    }

    record Session(InterviewResponse.Summary summary, String questionPlan, List<InterviewResponse.Answer> answers) {}
}
