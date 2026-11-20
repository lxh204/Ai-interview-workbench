create table if not exists knowledge_document (
    id varchar(36) primary key,
    title varchar(100) not null,
    created_at timestamp with time zone not null default current_timestamp
);

create table if not exists knowledge_chunk (
    document_id varchar(36) not null references knowledge_document(id) on delete cascade,
    chunk_index integer not null,
    content varchar(1200) not null,
    embedding clob,
    embedding_space varchar(400),
    primary key (document_id, chunk_index)
);

create table if not exists model_settings (
    id integer primary key,
    base_url varchar(280) not null,
    chat_model varchar(100) not null,
    embedding_model varchar(100) not null,
    encrypted_key varchar(1100) not null
);
create table if not exists scheduled_interview (
    id varchar(36) primary key,
    company varchar(100) not null,
    position varchar(100) not null,
    starts_at timestamp not null,
    kind varchar(10) not null,
    location varchar(300) not null,
    notes varchar(1000) not null,
    status varchar(12) not null
);

create table if not exists resume_review (
    id varchar(36) primary key,
    filename varchar(180) not null,
    role_label varchar(100) not null,
    body clob not null,
    original_file blob,
    fingerprint varchar(64) not null,
    task_state varchar(12) not null,
    feedback clob,
    fail_reason varchar(250),
    created_at timestamp with time zone not null default current_timestamp,
    unique (fingerprint, role_label)
);
create index if not exists resume_review_queue on resume_review(task_state, created_at);
alter table resume_review add column if not exists automatic_retry_count int not null default 0;
alter table resume_review add column if not exists next_attempt_at timestamp with time zone;

create table if not exists model_provider (
    id varchar(36) primary key,
    display_name varchar(80) not null,
    base_url varchar(280) not null,
    chat_model varchar(100) not null,
    embedding_model varchar(100) not null,
    encrypted_key varchar(1100) not null,
    active_flag boolean not null default false,
    created_at timestamp with time zone not null default current_timestamp
);

alter table scheduled_interview add column if not exists meeting_url varchar(500) not null default '';
alter table scheduled_interview add column if not exists interviewer varchar(100) not null default '';
alter table scheduled_interview add column if not exists round_number integer;
alter table scheduled_interview add column if not exists reminder_minutes integer not null default 0;
