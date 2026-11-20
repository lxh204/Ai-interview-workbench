CREATE TABLE IF NOT EXISTS interview_session (
    id VARCHAR(36) PRIMARY KEY,
    role_code VARCHAR(8) NOT NULL,
    next_index INT NOT NULL
);

CREATE TABLE IF NOT EXISTS interview_answer (
    session_id VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index INT NOT NULL,
    question_text VARCHAR(512) NOT NULL,
    answer_text CLOB NOT NULL,
    feedback_text CLOB NOT NULL,
    score INT,
    PRIMARY KEY (session_id, question_index)
);
