package com.interviewlab.workbench.knowledge;

import com.interviewlab.workbench.knowledge.KnowledgeAnswers.Turn;
import com.interviewlab.workbench.knowledge.KnowledgeConversations.Conversation;
import com.interviewlab.workbench.knowledge.KnowledgeConversations.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/knowledge/conversations")
public class KnowledgeChatController {
    private final KnowledgeConversations conversations;
    private final KnowledgeJson json;

    public KnowledgeChatController(KnowledgeConversations conversations, KnowledgeJson json) {
        this.conversations = conversations;
        this.json = json;
    }

    @GetMapping
    List<Summary> list() {
        return conversations.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Conversation create(@Valid @RequestBody NewConversation input) {
        return conversations.create(input.documentIds());
    }

    @GetMapping("/{id}")
    Conversation get(@PathVariable String id) {
        return conversations.get(id);
    }

    @PatchMapping("/{id}")
    Conversation update(@PathVariable String id, @Valid @RequestBody UpdateConversation input) {
        return conversations.update(id, input.title(), input.documentIds(), input.pinned());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable String id) {
        conversations.remove(id);
    }

    @PostMapping("/{id}/messages")
    Turn send(@PathVariable String id, @Valid @RequestBody KnowledgeController.Question input) {
        var pending = conversations.begin(id, UUID.randomUUID());
        try {
            return conversations.send(id, input.question().strip(), pending, null);
        } finally {
            conversations.release(id, pending);
        }
    }

    @PostMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> stream(@PathVariable String id, @Valid @RequestBody StreamQuestion input) {
        var pending = conversations.begin(id, input.requestId());
        StreamingResponseBody body = output -> {
            try {
                event(output, "ready", Map.of("requestId", input.requestId()));
                Turn turn = conversations.send(id, input.question().strip(), pending, delta -> {
                    pending.check();
                    event(output, "delta", Map.of("text", delta));
                });
                event(output, "done", turn);
            } catch (CancellationException | UncheckedIOException ignored) {
                // The requesting browser already closed or explicitly cancelled the stream.
            } catch (ResponseStatusException failure) {
                if (!Thread.currentThread().isInterrupted()) {
                    String message = failure.getReason() == null ? "问答未能完成。" : failure.getReason();
                    event(output, "failure", Map.of("message", message));
                }
            } finally {
                conversations.release(id, pending);
            }
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache").header("X-Accel-Buffering", "no").body(body);
    }

    @DeleteMapping("/{id}/requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable String id, @PathVariable UUID requestId) {
        conversations.cancel(id, requestId);
    }

    private void event(OutputStream output, String event, Object value) {
        try {
            output.write(("event: " + event + "\ndata: " + json.write(value) + "\n\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    record NewConversation(List<String> documentIds) {}
    record UpdateConversation(@Pattern(regexp = "(?s).*\\S.*", message = "请输入会话名称。")
                              @Size(max = 100, message = "会话名称最多 100 字符。") String title,
                              List<String> documentIds, Boolean pinned) {}
    record StreamQuestion(@NotNull(message = "缺少请求编号。") UUID requestId,
                          @NotBlank(message = "请输入问题。") @Size(max = 500, message = "问题最多 500 字符。") String question) {}
}
