-- Independent PostgreSQL migration; never part of the MySQL db/migration location.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE TABLE yufeichi_ai.embedding_profile (
    singleton boolean PRIMARY KEY CHECK (singleton),
    model text NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions > 0)
);
CREATE TABLE yufeichi_ai.vector_store (
    id uuid PRIMARY KEY,
    content text NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    embedding public.vector(${embeddingDimensions}) NOT NULL
);
-- Exact cosine search initially. No HNSW index or automatic table replacement.
