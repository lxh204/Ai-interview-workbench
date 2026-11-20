package com.interviewlab.workbench.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.ai.ModelGateway;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@EnabledIfEnvironmentVariable(named = "RESUME_TEST_REDIS_PORT", matches = "[0-9]+")
class ResumeRedisStreamTest {
    private static final String RESUME_TEXT = "使用 Spring Boot 开发订单管理服务，负责编写接口和处理状态转换，使用单元测试覆盖重复提交场景。";
    private final String stream = "workbench-test:resume:" + UUID.randomUUID();
    private final ConcurrentLinkedQueue<Integer> upstream = new ConcurrentLinkedQueue<>();
    private final AtomicInteger modelRequests = new AtomicInteger();
    private LettuceConnectionFactory redis;
    private StringRedisTemplate template;
    private HttpServer model;
    private JdbcTemplate jdbc;
    private String databaseSchema;
    private ResumeReviews jobs;
    private RedisResumeWorker worker;

    @BeforeEach
    void start() throws IOException {
        int redisPort = Integer.parseInt(System.getenv("RESUME_TEST_REDIS_PORT"));
        redis = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", redisPort));
        redis.afterPropertiesSet();
        template = new StringRedisTemplate(redis);

        String postgresUrl = System.getenv("RESUME_TEST_POSTGRES_URL");
        DriverManagerDataSource database;
        if (postgresUrl == null || postgresUrl.isBlank()) {
            database = new DriverManagerDataSource("jdbc:h2:mem:resume-stream-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
            jdbc = new JdbcTemplate(database);
        } else {
            database = new DriverManagerDataSource(postgresUrl, System.getenv("RESUME_TEST_POSTGRES_USER"),
                System.getenv().getOrDefault("RESUME_TEST_POSTGRES_PASSWORD", ""));
            jdbc = new JdbcTemplate(database);
            databaseSchema = "resume_stream_test_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("CREATE SCHEMA " + databaseSchema);
            database.setSchema(databaseSchema);
        }
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(database);
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                modelRequests.incrementAndGet();
                Integer next = upstream.poll();
                int code = next == null ? 200 : next;
                byte[] response = (code == 200
                    ? "{\"choices\":[{\"message\":{\"content\":\"简历分析结果\"}}]}" : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        model.start();
        var gateway = new ModelGateway(new ObjectMapper(), "fake-token", "http://127.0.0.1:" + model.getAddress().getPort(), "fake-model", "");
        jobs = new ResumeReviews(jdbc, new ResumeController(gateway));
        worker = new RedisResumeWorker(jobs, template, stream, Duration.ZERO);
    }

    @AfterEach
    void close() {
        if (template != null) template.delete(stream);
        if (redis != null) redis.destroy();
        if (model != null) model.stop(0);
        if (databaseSchema != null) {
            jdbc.execute("DROP SCHEMA " + databaseSchema + " CASCADE");
        } else if (jdbc != null) {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void completesOnlyOnceForDuplicateUploadsAndStreamDeliveries() {
        String id = enqueue();
        assertThat(enqueue()).isEqualTo(id);
        assertThat(template.hasKey(stream)).isFalse();
        worker.tick();

        assertThat(jobs.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(jobs.detail(id).feedback()).isEqualTo("简历分析结果");
        assertThat(jobs.list()).hasSize(1);
        assertThat(template.opsForStream().size(stream)).isZero();
        assertThat(template.opsForStream().pending(stream, RedisResumeWorker.GROUP).getTotalPendingMessages()).isZero();

        template.opsForStream().add(stream, Map.of("id", id, "version", "0"));
        worker.tick();
        assertThat(modelRequests.get()).isEqualTo(1);
        assertThat(template.opsForStream().size(stream)).isZero();
    }

    @Test
    void publishesADelayedRetryAsANewGenerationAndAcknowledgesBothDeliveries() {
        upstream.addAll(List.of(503, 200));
        String id = enqueue();
        worker.tick();
        assertThat(jobs.detail(id).status()).isEqualTo("PENDING");
        assertThat(jobs.detail(id).error()).isNull();
        assertThat(template.opsForStream().size(stream)).isZero();
        assertThat(version(id)).isEqualTo(1);

        jdbc.update("UPDATE resume_review SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE id = ?", id);
        worker.tick();
        assertThat(jobs.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(modelRequests.get()).isEqualTo(2);
        assertThat(template.opsForStream().pending(stream, RedisResumeWorker.GROUP).getTotalPendingMessages()).isZero();
    }

    @Test
    void reclaimsAnAbandonedMessageAndRecoversItsExpiredDatabaseLease() {
        worker.tick();
        String id = enqueue();
        template.opsForStream().add(stream, Map.of("id", id, "version", "0"));
        var abandoned = template.<String, String>opsForStream().read(Consumer.from(RedisResumeWorker.GROUP, "stopped-worker"),
            StreamOffset.create(stream, ReadOffset.lastConsumed()));
        assertThat(abandoned).hasSize(1);
        jdbc.update("""
            UPDATE resume_review SET task_state = 'RUNNING', queue_lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND
            WHERE id = ?
            """, id);

        var restarted = new RedisResumeWorker(jobs, template, stream, Duration.ZERO);
        restarted.tick();
        restarted.tick();
        assertThat(version(id)).isEqualTo(1);
        assertThat(jobs.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(modelRequests.get()).isEqualTo(1);
        assertThat(template.opsForStream().pending(stream, RedisResumeWorker.GROUP).getTotalPendingMessages()).isZero();
        assertThat(template.opsForStream().size(stream)).isZero();
    }

    @Test
    void republishesTheDatabaseOutboxAfterRedisLosesAStream() {
        worker.tick();
        String id = enqueue();
        var queued = jobs.awaitingPublication(1).getFirst();
        template.opsForStream().add(stream, Map.of("id", id, "version", "0"));
        jobs.markPublished(queued);
        assertThat(jobs.awaitingPublication(1)).isEmpty();
        template.delete(stream);
        jdbc.update("""
            UPDATE resume_review SET queue_published_at = CURRENT_TIMESTAMP - INTERVAL '61' SECOND WHERE id = ?
            """, id);

        var restarted = new RedisResumeWorker(jobs, template, stream, Duration.ZERO);
        restarted.tick();
        assertThat(jobs.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(version(id)).isZero();
        assertThat(modelRequests.get()).isEqualTo(1);
        assertThat(template.opsForStream().pending(stream, RedisResumeWorker.GROUP).getTotalPendingMessages()).isZero();
    }

    private String enqueue() {
        return jobs.enqueue("订单系统.txt", "Java 后端", RESUME_TEXT, null).id();
    }

    private Long version(String id) {
        return jdbc.queryForObject("SELECT queue_version FROM resume_review WHERE id = ?", Long.class, id);
    }
}
