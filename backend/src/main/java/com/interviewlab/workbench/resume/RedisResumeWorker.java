package com.interviewlab.workbench.resume;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.resume.queue", havingValue = "redis")
class RedisResumeWorker {
    static final String GROUP = "resume-analysis";
    private static final Logger log = LoggerFactory.getLogger(RedisResumeWorker.class);
    private static final RedisScript<Long> ACKNOWLEDGE = RedisScript.of("""
        local acknowledged = redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
        if acknowledged == 1 then
            redis.call('XDEL', KEYS[1], ARGV[2])
        end
        return acknowledged
        """, Long.class);

    private final ResumeReviews jobs;
    private final StringRedisTemplate redis;
    private final StreamOperations<String, String, String> streams;
    private final String key;
    private final String workerId = UUID.randomUUID().toString();
    private final Duration pendingIdle;
    private boolean groupReady;
    private Instant nextWarning = Instant.EPOCH;

    @Autowired
    RedisResumeWorker(ResumeReviews jobs, StringRedisTemplate redis,
                      @Value("${workbench.resume.stream:interview-workbench:resume-reviews}") String key) {
        this(jobs, redis, key, Duration.ofMinutes(2));
    }

    RedisResumeWorker(ResumeReviews jobs, StringRedisTemplate redis, String key, Duration pendingIdle) {
        this.jobs = jobs;
        this.redis = redis;
        this.streams = redis.opsForStream();
        this.key = key;
        this.pendingIdle = pendingIdle;
    }

    @Scheduled(initialDelay = 1600, fixedDelay = 600)
    void tick() {
        try {
            ensureGroup();
            jobs.recoverExpired();
            publishDue();
            reclaimPending();
            var deliveries = streams.read(Consumer.from(GROUP, workerId), StreamReadOptions.empty().count(1),
                StreamOffset.create(key, ReadOffset.lastConsumed()));
            if (deliveries != null) {
                deliveries.forEach(this::consume);
            }
        } catch (DataAccessException failure) {
            groupReady = false;
            Instant now = Instant.now();
            if (!now.isBefore(nextWarning)) {
                log.warn("Resume queue temporarily unavailable ({})", failure.getClass().getSimpleName());
                nextWarning = now.plusSeconds(30);
            }
        }
    }

    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            streams.createGroup(key, ReadOffset.from("0-0"), GROUP);
        } catch (DataAccessException failure) {
            String reason = failure.getMostSpecificCause().getMessage();
            if (reason == null || !reason.startsWith("BUSYGROUP")) {
                throw failure;
            }
        }
        groupReady = true;
    }

    // The database row acts as the outbox. Republishing a generation also covers a lost Redis stream.
    private void publishDue() {
        for (var review : jobs.awaitingPublication(8)) {
            var published = streams.add(key, Map.of("id", review.id(), "version", Long.toString(review.version())));
            if (published != null) {
                jobs.markPublished(review);
            }
        }
    }

    private void reclaimPending() {
        var pending = streams.pending(key, GROUP, Range.unbounded(), 32);
        if (pending == null) {
            return;
        }
        pending.stream().filter(delivery -> delivery.getElapsedTimeSinceLastDelivery().compareTo(pendingIdle) >= 0)
            .findFirst().ifPresent(delivery -> streams.claim(key, GROUP, workerId, pendingIdle, delivery.getId()).forEach(this::consume));
    }

    private void consume(MapRecord<String, String, String> delivery) {
        var fields = delivery.getValue();
        String id = fields.get("id");
        Long version = version(fields.get("version"));
        if (id != null && id.length() == 36 && version != null && version >= 0) {
            jobs.consume(id, version);
        }
        // There is one consumer group for this application-owned stream. Remove the record after ACK.
        redis.execute(ACKNOWLEDGE, List.of(key), GROUP, delivery.getId().getValue());
    }

    private Long version(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
