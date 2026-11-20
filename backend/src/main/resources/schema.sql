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
