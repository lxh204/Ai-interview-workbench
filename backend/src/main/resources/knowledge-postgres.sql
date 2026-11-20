CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE knowledge_chunk ADD COLUMN IF NOT EXISTS embedding_vector vector;

CREATE INDEX IF NOT EXISTS knowledge_chunk_vector_scope
    ON knowledge_chunk (embedding_space, vector_dims(embedding_vector), document_id)
    WHERE embedding_vector IS NOT NULL;