package com.interviewlab.workbench.resume;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@DependsOnDatabaseInitialization
public class ResumeReviews {
    private static final String SUMMARY_COLUMNS = "id, filename, role_label, task_state, feedback, fail_reason, created_at";
    private final JdbcTemplate jdbc;
    private final ResumeController analyzer;

    public ResumeReviews(JdbcTemplate jdbc, ResumeController analyzer) {
        this.jdbc = jdbc;
        this.analyzer = analyzer;
    }

    @PostConstruct
    void recover() {
        jdbc.update("update resume_review set task_state = 'PENDING' where task_state = 'RUNNING'");
    }

    Review enqueue(String title, String role, String text, byte[] bytes) {
        String id = UUID.randomUUID().toString();
        String hash = hash(bytes == null ? text.getBytes(StandardCharsets.UTF_8) : bytes);
        try {
            jdbc.update("""
                insert into resume_review(id, filename, role_label, body, original_file, fingerprint, task_state)
                values (?, ?, ?, ?, ?, ?, 'PENDING')
                """, id, title, role, text, bytes, hash);
        } catch (DuplicateKeyException ex) {
            List<Review> matches = jdbc.query("select " + SUMMARY_COLUMNS + " from resume_review where fingerprint = ? and role_label = ?",
                (rs, row) -> read(rs, null), hash, role);
            if (matches.isEmpty()) {
                throw ex;
            }
            return matches.getFirst();
        }
        return detail(id);
    }

    List<Review> list() {
        return jdbc.query("select " + SUMMARY_COLUMNS + " from resume_review order by created_at desc, id",
            (rs, row) -> read(rs, null));
    }

    Review detail(String id) {
        List<Review> matches = jdbc.query("select " + SUMMARY_COLUMNS + ", body from resume_review where id = ?",
            (rs, row) -> read(rs, rs.getString("body")), id);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这份简历。");
        }
        return matches.getFirst();
    }

    byte[] originalFile(String id) {
        Review review = detail(id);
        List<byte[]> stored = jdbc.query("select original_file from resume_review where id = ?",
            (rs, row) -> rs.getBytes(1), id);
        if (!stored.isEmpty() && stored.getFirst() != null) {
            return stored.getFirst();
        }
        return review.text().getBytes(StandardCharsets.UTF_8);
    }

    void retry(String id) {
        detail(id);
        if (jdbc.update("update resume_review set task_state='PENDING', fail_reason=null where id=? and task_state='FAILED'", id) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试分析失败的简历。");
        }
    }

    void delete(String id) {
        if (jdbc.update("delete from resume_review where id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这份简历。");
        }
    }

    @Scheduled(initialDelay = 1600, fixedDelay = 600)
    void work() {
        List<String> pending = jdbc.queryForList("""
            select id from resume_review where task_state='PENDING' order by created_at, id limit 1
            """, String.class);
        if (pending.isEmpty()) {
            return;
        }
        String id = pending.getFirst();
        if (jdbc.update("update resume_review set task_state='RUNNING' where id=? and task_state='PENDING'", id) == 0) {
            return;
        }
        try {
            Review row = detail(id);
            var analyzed = analyzer.review(row.text(), row.role());
            jdbc.update("update resume_review set task_state='COMPLETED', feedback=?, fail_reason=null where id=? and task_state='RUNNING'",
                analyzed.feedback(), id);
        } catch (ResponseStatusException ex) {
            fail(id, ex.getReason() == null ? "分析未完成，请重试。" : ex.getReason());
        } catch (RuntimeException ex) {
            fail(id, "分析任务未完成，请重试。");
        }
    }

    private void fail(String id, String reason) {
        String shortReason = reason.length() <= 250 ? reason : reason.substring(0, 250);
        jdbc.update("update resume_review set task_state='FAILED', fail_reason=? where id=? and task_state='RUNNING'", shortReason, id);
    }

    private Review read(ResultSet rs, String text) throws SQLException {
        return new Review(rs.getString("id"), rs.getString("filename"), rs.getString("role_label"), rs.getString("task_state"),
            rs.getString("feedback"), rs.getString("fail_reason"), rs.getObject("created_at", OffsetDateTime.class).toInstant(), text);
    }

    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    record Review(String id, String filename, String role, String status, String feedback, String error,
                  Instant createdAt, String text) {}
}
