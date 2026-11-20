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
    question_plan text NOT NULL,
    PRIMARY KEY (session_id, question_index)
);

CREATE TABLE IF NOT EXISTS interview_answer (
    session_id VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index INT NOT NULL,
    question_text VARCHAR(512) NOT NULL,
    answer_text text NOT NULL,
    feedback_text text NOT NULL,
    score INT,
    PRIMARY KEY (session_id, question_index)
);

ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS skipped_count INT DEFAULT 0 NOT NULL;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS resume_id VARCHAR(36);
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS resume_name VARCHAR(255);
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS resume_snapshot text;
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS provider_id VARCHAR(64);
ALTER TABLE interview_session ADD COLUMN IF NOT EXISTS draft_text text;
ALTER TABLE interview_answer ADD COLUMN IF NOT EXISTS skipped BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE interview_answer ADD COLUMN IF NOT EXISTS follow_up_of INT;

CREATE TABLE IF NOT EXISTS interview_bookmark (
    session_id VARCHAR(36) NOT NULL REFERENCES interview_session(id) ON DELETE CASCADE,
    question_index INT NOT NULL,
    PRIMARY KEY (session_id, question_index)
);
