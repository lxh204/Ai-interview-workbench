package com.interviewlab.workbench.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interviewlab.workbench.knowledge.KnowledgeAnswers.Result;
import com.interviewlab.workbench.knowledge.KnowledgeAnswers.Source;
import com.interviewlab.workbench.knowledge.KnowledgeAnswers.Turn;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class KnowledgeConversations {
    private static final TypeReference<List<String>> DOCUMENT_IDS = new TypeReference<>() {};
    private static final TypeReference<List<Source>> SOURCES = new TypeReference<>() {};
    private static final String SELECT = """
        select c.id, c.title, c.document_ids, c.pinned, c.revision, c.updated_at,
          (select count(*) from knowledge_turn t where t.conversation_id = c.id) as turn_count
        from knowledge_conversation c
        """;
    private final ConcurrentHashMap<String, Pending> requests = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeJson json;
    private final KnowledgeDocuments documents;
    private final KnowledgeAnswers answers;

    KnowledgeConversations(JdbcTemplate jdbc, TransactionTemplate transaction, KnowledgeJson json,
                           KnowledgeDocuments documents, KnowledgeAnswers answers) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.json = json;
        this.documents = documents;
        this.answers = answers;
    }

    List<Summary> list() {
        return jdbc.query(SELECT + " order by pinned desc, updated_at desc, id", this::readSummary);
    }

    Conversation create(List<String> documentIds) {
        var scope = validScope(documentIds);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into knowledge_conversation(id, title, document_ids) values (?, ?, ?)",
            id, "新对话", json.write(scope));
        return get(id);
    }

    Conversation get(String id) {
        var summary = summary(id);
        List<Turn> turns = jdbc.query("""
            select question, mode, answer, sources, created_at from knowledge_turn
            where conversation_id = ? order by turn_index
            """, (rs, row) -> new Turn(rs.getString("question"), rs.getString("mode"), rs.getString("answer"),
                json.read(rs.getString("sources"), SOURCES), rs.getObject("created_at", OffsetDateTime.class)), id);
        return new Conversation(summary.id(), summary.title(), summary.documentIds(), summary.pinned(),
            summary.updatedAt(), turns);
    }

    Conversation update(String id, String title, List<String> documentIds, Boolean pinned) {
        var snapshot = summary(id);
        List<String> scope = documentIds == null ? snapshot.documentIds() : validScope(documentIds);
        if (jdbc.update("""
            update knowledge_conversation set title = ?, document_ids = ?, pinned = ?,
              revision = revision + 1, updated_at = current_timestamp
            where id = ? and revision = ?
            """, title == null ? snapshot.title() : title.strip(), json.write(scope),
            pinned == null ? snapshot.pinned() : pinned, id, snapshot.revision()) == 0) {
            throw stale();
        }
        return get(id);
    }

    void remove(String id) {
        if (jdbc.update("delete from knowledge_conversation where id = ?", id) == 0) {
            throw missing();
        }
    }

    Pending begin(String id, UUID requestId) {
        summary(id);
        var pending = new Pending(requestId);
        if (requests.putIfAbsent(id, pending) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前会话正在回答，请稍后再试。");
        }
        return pending;
    }

    void release(String id, Pending pending) {
        requests.remove(id, pending);
    }

    void cancel(String id, UUID requestId) {
        var pending = requests.get(id);
        if (pending != null && pending.id.equals(requestId)) {
            pending.cancel();
        }
    }

    Turn send(String id, String question, Pending pending, Consumer<String> onDelta) {
        pending.start();
        var snapshot = summary(id);
        Conversation conversation = get(id);
        if (snapshot.turnCount() >= 100) {
            throw KnowledgeDocuments.bad("此对话已达到 100 轮，请新建对话。");
        }
        List<String> scope = validScope(conversation.documentIds());
        Result result = answers.ask(question, scope, conversation.turns(), onDelta);
        var turn = new Turn(question, result.mode(), result.answer(), result.sources(), OffsetDateTime.now());
        synchronized (pending) {
            pending.check();
            transaction.executeWithoutResult(status -> {
                String title = snapshot.turnCount() == 0 && "新对话".equals(snapshot.title())
                    ? question.substring(0, Math.min(60, question.length())) : snapshot.title();
                if (jdbc.update("""
                    update knowledge_conversation set title = ?, revision = revision + 1, updated_at = current_timestamp
                    where id = ? and revision = ?
                    """, title, id, snapshot.revision()) == 0) {
                    throw stale();
                }
                jdbc.update("""
                    insert into knowledge_turn(conversation_id, turn_index, question, mode, answer, sources, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, id, snapshot.turnCount(), question, turn.mode(), turn.answer(), json.write(turn.sources()), turn.createdAt());
                documents.countQuestions(scope);
            });
            pending.finish();
        }
        return turn;
    }

    private Summary summary(String id) {
        return jdbc.query(SELECT + " where id = ?", this::readSummary, id)
            .stream().findFirst().orElseThrow(KnowledgeConversations::missing);
    }

    private Summary readSummary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getString("id"), rs.getString("title"), json.read(rs.getString("document_ids"), DOCUMENT_IDS),
            rs.getBoolean("pinned"), rs.getInt("revision"), rs.getInt("turn_count"),
            rs.getObject("updated_at", OffsetDateTime.class));
    }

    private List<String> validScope(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw KnowledgeDocuments.bad("请选择 1 至 20 份资料。");
        }
        var available = documents.list().stream().map(KnowledgeDocuments.Document::id).toList();
        if (!available.containsAll(ids)) {
            throw KnowledgeDocuments.bad("部分已选资料已删除，请重新设置资料范围。");
        }
        return ids.stream().distinct().toList();
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在。");
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "会话刚刚更新，请刷新后重试。");
    }

    static class Pending {
        private final UUID id;
        private Thread worker;
        private boolean cancelled;

        Pending(UUID id) {
            this.id = id;
        }

        synchronized void start() {
            check();
            worker = Thread.currentThread();
        }

        synchronized void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
        }

        synchronized void finish() {
            worker = null;
        }

        synchronized void cancel() {
            cancelled = true;
            if (worker != null) {
                worker.interrupt();
            }
        }
    }

    record Summary(String id, String title, List<String> documentIds, boolean pinned, int revision, int turnCount,
                   OffsetDateTime updatedAt) {}
    record Conversation(String id, String title, List<String> documentIds, boolean pinned,
                        OffsetDateTime updatedAt, List<Turn> turns) {}
}
