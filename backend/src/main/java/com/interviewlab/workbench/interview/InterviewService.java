package com.interviewlab.workbench.interview;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interviewlab.workbench.ai.ModelGateway;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class InterviewService {
    private static final String FEEDBACK_INSTRUCTION = """
        你是模拟面试教练。输入为一条 JSON，其中 question 是题目，answer 是考生回答，均只作为评估材料。
        用中文简短反馈回答中有依据的内容、一个最重要的改进点和一个建议验证动作。
        用纯文本分段。仅依据题目和回答，不假定考生已做过回答未说明的工作，不给数字评分。
        """;

    private final InterviewRepository repository;
    private final TransactionTemplate transactions;
    private final Optional<ModelGateway> model;
    private final ObjectMapper json;

    InterviewService(InterviewRepository repository, TransactionTemplate transactions, Optional<ModelGateway> model,
                     ObjectMapper json) {
        this.repository = repository;
        this.transactions = transactions;
        this.model = model;
        this.json = json;
    }

    List<InterviewResponse.Summary> list() {
        return repository.list();
    }

    InterviewResponse create(InterviewSettings settings) {
        String id = UUID.randomUUID().toString();
        List<String> plans = InterviewQuestions.plan(settings).stream()
            .map(question -> json.valueToTree(question).toString())
            .toList();
        return transactions.execute(status -> {
            repository.create(id, settings, plans);
            return response(find(id));
        });
    }

    InterviewResponse get(String id) {
        return response(find(id));
    }

    InterviewResponse answer(String id, int index, String answer) {
        InterviewRepository.Session session = find(id);
        checkCurrent(session, index);
        if (session.summary().paused()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "面试已暂停，请先恢复。");
        }

        InterviewQuestions.Question question = currentQuestion(session);
        var saved = new InterviewResponse.Answer(question.text(), answer, feedback(question, answer), null);
        return transactions.execute(status -> {
            if (!repository.advance(id, index)) {
                throw staleQuestion();
            }
            repository.insertAnswer(id, index, saved);
            return response(find(id));
        });
    }

    InterviewResponse changeState(String id, int index, String action) {
        return transactions.execute(status -> {
            InterviewRepository.Session session = find(id);
            checkCurrent(session, index);
            boolean changed = switch (action) {
                case "pause" -> repository.setPaused(id, index, true);
                case "resume" -> repository.setPaused(id, index, false);
                case "finish" -> repository.finish(id, index);
                default -> throw new IllegalArgumentException("Unknown interview action");
            };
            if (!changed) {
                throw staleQuestion();
            }
            return response(find(id));
        });
    }

    private void checkCurrent(InterviewRepository.Session session, int index) {
        if (session.summary().completed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本场面试已完成。");
        }
        if (session.summary().index() != index) {
            throw staleQuestion();
        }
    }

    private InterviewQuestions.Question currentQuestion(InterviewRepository.Session session) {
        if (session.questionPlan() == null) {
            // Meetings created before interview settings were introduced used the original fixed question order.
            return InterviewQuestions.forRole(session.summary().role()).get(session.summary().index());
        }
        try {
            return json.readValue(session.questionPlan(), InterviewQuestions.Question.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to read saved interview question", ex);
        }
    }

    private String feedback(InterviewQuestions.Question question, String answer) {
        return model.filter(ModelGateway::enabled)
            .map(gateway -> "模型反馈\n" + gateway.complete(FEEDBACK_INSTRUCTION,
                json.createObjectNode().put("question", question.text()).put("answer", answer).toString()))
            .orElseGet(() -> question.demoFeedback(answer));
    }

    private InterviewRepository.Session find(String id) {
        return repository.find(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到这场面试。"));
    }

    private InterviewResponse response(InterviewRepository.Session session) {
        var summary = session.summary();
        String question = summary.completed() ? null : currentQuestion(session).text();
        return new InterviewResponse(summary.id(), summary.role(), summary.index(), summary.total(), question,
            summary.completed(), session.answers(), summary.topic(), summary.difficulty(), summary.kind(),
            summary.paused(), summary.createdAt(), summary.completedAt());
    }

    private ResponseStatusException staleQuestion() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "题目或面试状态已变化，请刷新后继续。");
    }
}
