package com.interviewlab.workbench.interview;

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

    InterviewResponse create(String role) {
        String id = UUID.randomUUID().toString();
        repository.create(id, role);
        return response(id, find(id));
    }

    InterviewResponse get(String id) {
        return response(id, find(id));
    }

    InterviewResponse answer(String id, int index, String answer) {
        InterviewRepository.Session session = find(id);
        List<InterviewQuestions.Question> questions = InterviewQuestions.forRole(session.role());
        if (session.index() >= questions.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "本场面试已完成。");
        }
        if (session.index() != index) {
            throw staleQuestion();
        }

        InterviewQuestions.Question question = questions.get(index);
        var saved = new InterviewResponse.Answer(question.text(), answer, feedback(question, answer), null);
        return transactions.execute(status -> {
            if (!repository.advance(id, index)) {
                throw staleQuestion();
            }
            repository.insertAnswer(id, index, saved);
            return response(id, find(id));
        });
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

    private InterviewResponse response(String id, InterviewRepository.Session session) {
        List<InterviewQuestions.Question> questions = InterviewQuestions.forRole(session.role());
        boolean completed = session.index() >= questions.size();
        String currentQuestion = completed ? null : questions.get(session.index()).text();
        return new InterviewResponse(id, session.role(), session.index(), questions.size(), currentQuestion,
            completed, session.answers());
    }

    private ResponseStatusException staleQuestion() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "题目进度已变化，请刷新面试后继续。");
    }
}
