package com.interviewlab.workbench.resume;

import com.interviewlab.workbench.ai.ModelGateway;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@DependsOnDatabaseInitialization
public class ResumeReviews {
    private static final List<Integer> RETRY_DELAYS_SECONDS = List.of(1, 2, 4);
    private static final String SUMMARY_COLUMNS = "id, filename, role_label, task_state, feedback, fail_reason, created_at";
    private final JdbcTemplate jdbc;
    private final ResumeController analyzer;

    public ResumeReviews(JdbcTemplate jdbc, ResumeController analyzer) {
        this.jdbc = jdbc;
        this.analyzer = analyzer;
    }

    void recover() {
        resetRunning("where task_state = 'RUNNING'");
    }

    void recoverExpired() {
        resetRunning("""
            where task_state = 'RUNNING' and (queue_lease_until is null or queue_lease_until <= current_timestamp)
            """);
    }

    private void resetRunning(String predicate) {
        jdbc.update("""
            update resume_review set task_state = 'PENDING', next_attempt_at = null, queue_version = queue_version + 1,
                queue_published_at = null, queue_lease_until = null
            """ + predicate);
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
        if (jdbc.update("""
            update resume_review set task_state='PENDING', fail_reason=null, automatic_retry_count=0, next_attempt_at=null,
                queue_version=queue_version+1, queue_published_at=null, queue_lease_until=null
            where id=? and task_state='FAILED'
            """, id) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能重试分析失败的简历。");
        }
    }

    void delete(String id) {
        if (jdbc.update("delete from resume_review where id=?", id) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这份简历。");
        }
    }

    void work() {
        jdbc.query("""
            select id, automatic_retry_count, queue_version from resume_review where task_state='PENDING'
                and (next_attempt_at is null or next_attempt_at <= current_timestamp)
            order by created_at, id limit 1
            """, this::pendingReview).stream().findFirst().ifPresent(this::process);
    }

    List<PendingReview> awaitingPublication(int limit) {
        return jdbc.query("""
            select id, automatic_retry_count, queue_version from resume_review where task_state='PENDING'
                and (next_attempt_at is null or next_attempt_at <= current_timestamp)
                and (queue_published_at is null or queue_published_at <= current_timestamp - interval '1' minute)
            order by created_at, id limit ?
            """, this::pendingReview, limit);
    }

    void markPublished(PendingReview review) {
        jdbc.update("""
            update resume_review set queue_published_at=current_timestamp
            where id=? and queue_version=? and task_state='PENDING'
            """, review.id(), review.version());
    }

    void consume(String id, long version) {
        jdbc.query("""
            select id, automatic_retry_count, queue_version from resume_review
            where id=? and queue_version=? and task_state='PENDING'
            """, this::pendingReview, id, version).stream().findFirst().ifPresent(this::process);
    }

    private void process(PendingReview selected) {
        String id = selected.id();
        if (jdbc.update("""
            update resume_review set task_state='RUNNING', next_attempt_at=null,
                queue_lease_until=current_timestamp + interval '2' minute
            where id=? and queue_version=? and task_state='PENDING'
                and (next_attempt_at is null or next_attempt_at <= current_timestamp)
            """, id, selected.version()) == 0) {
            return;
        }
        String feedback;
        try {
            Review row = detail(id);
            feedback = analyzer.review(row.text(), row.role()).feedback();
        } catch (ResponseStatusException ex) {
            if (ex instanceof ModelGateway.RetryableFailure && selected.retries() < RETRY_DELAYS_SECONDS.size()) {
                reschedule(selected);
            } else {
                fail(selected, ex.getReason() == null ? "分析未完成，请重试。" : ex.getReason());
            }
            return;
        } catch (DataAccessException ex) {
            // Preserve the delivery until the database can be reached again.
            throw ex;
        } catch (RuntimeException ex) {
            fail(selected, "分析任务未完成，请重试。");
            return;
        }
        jdbc.update("""
            update resume_review set task_state='COMPLETED', feedback=?, fail_reason=null, queue_lease_until=null
            where id=? and queue_version=? and task_state='RUNNING'
            """, feedback, id, selected.version());
    }

    private void reschedule(PendingReview review) {
        int delaySeconds = RETRY_DELAYS_SECONDS.get(review.retries());
        jdbc.update("""
            update resume_review set task_state='PENDING', fail_reason=null, automatic_retry_count=automatic_retry_count+1,
                next_attempt_at=current_timestamp + cast(? as integer) * interval '1' second,
                queue_version=queue_version+1, queue_published_at=null, queue_lease_until=null
            where id=? and queue_version=? and task_state='RUNNING'
            """, delaySeconds, review.id(), review.version());
    }

    private void fail(PendingReview review, String reason) {
        String shortReason = reason.length() <= 250 ? reason : reason.substring(0, 250);
        jdbc.update("""
            update resume_review set task_state='FAILED', fail_reason=?, queue_lease_until=null
            where id=? and queue_version=? and task_state='RUNNING'
            """, shortReason, review.id(), review.version());
    }

    private PendingReview pendingReview(ResultSet row, int rowIndex) throws SQLException {
        return new PendingReview(row.getString("id"), row.getInt("automatic_retry_count"), row.getLong("queue_version"));
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

    record PendingReview(String id, int retries, long version) {}

    record Review(String id, String filename, String role, String status, String feedback, String error,
                  Instant createdAt, String text) {}
}
