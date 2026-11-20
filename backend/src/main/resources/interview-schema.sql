CREATE TABLE IF NOT EXISTS interview_session (
    id VARCHAR(36) PRIMARY KEY,
    role_code VARCHAR(8) NOT NULL,
    next_index INT NOT NULL
);

ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS topic_code VARCHAR(16) DEFAULT 'general' NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS difficulty_code VARCHAR(8) DEFAULT 'junior' NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS question_count INT DEFAULT 3 NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS kind_code VARCHAR(8) DEFAULT 'text' NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS paused BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE IF NOT EXISTS interview_question (
    session_id VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index INT NOT NULL,
    question_plan CLOB NOT NULL,
    PRIMARY KEY (session_id, question_index)
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
