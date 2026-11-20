package com.interviewlab.workbench.resume;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.ai.ModelGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ResumeReviewRetryTest {
    private static final String REVIEW_TEXT = "负责 Java 后端开发，使用 Spring Boot 实现订单管理及状态转换，并编写单元测试覆盖重复提交场景。";
    private final Queue<Integer> modelStatuses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private HttpServer provider;
    private JdbcTemplate jdbc;
    private ResumeController analyzer;
    private ResumeReviews queue;

    @BeforeEach
    void startQueue() throws IOException {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:resume-retry-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        provider = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        provider.createContext("/chat/completions", this::respond);
        provider.start();
        String base = "http://127.0.0.1:" + provider.getAddress().getPort();
        analyzer = new ResumeController(new ModelGateway(new ObjectMapper(), "test-token", base, "test-model", ""));
        queue = new ResumeReviews(jdbc, analyzer);
    }

    @AfterEach
    void closeQueue() {
        if (provider != null) {
            provider.stop(0);
        }
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void retriesRateLimitingAndTemporaryServerFailuresThenSavesOneReview() {
        modelStatuses.addAll(List.of(408, 429, 503, 200));
        String id = enqueue("候选人 A");

        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        assertThat(queue.detail(id).error()).isNull();
        makeDue(id);
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        makeDue(id);
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        makeDue(id);
        queue.work();

        assertThat(queue.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(queue.detail(id).feedback()).isEqualTo("模型分析结果");
        assertThat(queue.list()).hasSize(1);
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void stopsAfterTheInitialCallAndThreeAutomaticRetriesAndManualRetryResetsTheBudget() {
        String id = enqueue("候选人 B");
        queue.work();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(queue.detail(id).status()).isEqualTo("PENDING");
            makeDue(id);
            queue.work();
        }
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("FAILED");
        assertThat(queue.detail(id).error()).contains("HTTP 503");
        assertThat(requests.get()).isEqualTo(4);

        queue.retry(id);
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        assertThat(queue.detail(id).error()).isNull();
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        assertThat(requests.get()).isEqualTo(5);
    }

    @Test
    void keepsAnInvalidCredentialFailureUntilTheUserManuallyRetries() {
        modelStatuses.addAll(List.of(401, 200));
        String id = enqueue("候选人 C");
        queue.work();
        queue.recover();
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("FAILED");
        assertThat(queue.detail(id).error()).contains("HTTP 401");
        assertThat(requests.get()).isEqualTo(1);

        queue.retry(id);
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("COMPLETED");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    void aReviewWaitingToRetryDoesNotBlockOthersAndRecoveryKeepsItsRetryBudget() {
        modelStatuses.addAll(List.of(502, 200, 503, 503, 503));
        String first = enqueue("候选人 D");
        queue.work();
        assertThat(queue.detail(first).status()).isEqualTo("PENDING");
        jdbc.update("UPDATE resume_review SET next_attempt_at = DATEADD('MINUTE', 1, CURRENT_TIMESTAMP) WHERE id = ?", first);
        String second = enqueue("候选人 E");
        queue.work();
        assertThat(queue.detail(second).status()).isEqualTo("COMPLETED");
        assertThat(queue.detail(first).status()).isEqualTo("PENDING");

        jdbc.update("UPDATE resume_review SET task_state = 'RUNNING', next_attempt_at = NULL WHERE id = ?", first);
        queue = new ResumeReviews(jdbc, analyzer);
        queue.recover();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(queue.detail(first).status()).isEqualTo("PENDING");
            makeDue(first);
            queue.work();
        }
        assertThat(queue.detail(first).status()).isEqualTo("FAILED");
        assertThat(requests.get()).isEqualTo(5);
    }

    @Test
    void aFailedConnectionRemainsRetryable() {
        provider.stop(0);
        provider = null;
        String id = enqueue("候选人 F");
        queue.work();
        assertThat(queue.detail(id).status()).isEqualTo("PENDING");
        assertThat(queue.detail(id).error()).isNull();
    }

    private String enqueue(String title) {
        return queue.enqueue(title + ".txt", "Java 开发", title + "。" + REVIEW_TEXT, null).id();
    }

    private void makeDue(String id) {
        jdbc.update("UPDATE resume_review SET next_attempt_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", id);
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            Integer next = modelStatuses.poll();
            int status = next == null ? 503 : next;
            byte[] body = (status == 200 ? "{\"choices\":[{\"message\":{\"content\":\"模型分析结果\"}}]}" : "{}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
