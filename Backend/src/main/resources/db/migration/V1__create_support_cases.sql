CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE support_cases (
    id BIGSERIAL PRIMARY KEY,
    tweet_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    customer_message TEXT NOT NULL,
    support_response TEXT NOT NULL,
    intent VARCHAR(100) NOT NULL,
    embedding vector(${embedding_dimension}) NOT NULL,
    CONSTRAINT uq_support_cases_tweet_id UNIQUE (tweet_id)
);

CREATE INDEX idx_support_cases_embedding_cosine
    ON support_cases USING hnsw (embedding vector_cosine_ops);