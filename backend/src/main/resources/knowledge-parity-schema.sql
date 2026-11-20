alter table knowledge_document add column if not exists category varchar(100) not null default '';
alter table knowledge_document add column if not exists filename varchar(255);
alter table knowledge_document add column if not exists original_file blob;
alter table knowledge_document add column if not exists original_text clob;

create table if not exists knowledge_conversation (
    id varchar(36) primary key,
    title varchar(100) not null,
    document_ids clob not null,
    pinned boolean not null default false,
    revision integer not null default 0,
    updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists knowledge_turn (
    conversation_id varchar(36) not null references knowledge_conversation(id) on delete cascade,
    turn_index integer not null,
    question varchar(500) not null,
    mode varchar(10) not null,
    answer clob not null,
    sources clob not null,
    primary key (conversation_id, turn_index)
);

create table if not exists knowledge_question (
    id varchar(36) primary key,
    document_id varchar(36) not null references knowledge_document(id) on delete cascade,
    question varchar(600) not null,
    reference_answer clob not null,
    category varchar(100) not null,
    difficulty varchar(10) not null,
    status varchar(10) not null,
    source_index integer,
    created_at timestamp with time zone not null default current_timestamp
);

create table if not exists knowledge_practice (
    id varchar(36) primary key,
    document_id varchar(36) not null,
    document_title varchar(100) not null,
    questions clob not null,
    current_index integer not null default 0,
    total integer not null,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp
);

create table if not exists knowledge_practice_answer (
    practice_id varchar(36) not null references knowledge_practice(id) on delete cascade,
    answer_index integer not null,
    content varchar(5000) not null,
    feedback clob not null,
    primary key (practice_id, answer_index)
);
