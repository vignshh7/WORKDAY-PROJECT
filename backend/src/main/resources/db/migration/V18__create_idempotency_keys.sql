-- Backs idempotency-key support for write operations that must be safely retryable
-- (Phase 12's interview booking; any later phase can reuse it under its own scope).
-- The scope+key_value unique constraint is the actual concurrency guard: a second
-- concurrent request with the same key blocks on this row at INSERT time (Postgres MVCC),
-- then either fails cleanly with a unique violation (first request committed) or succeeds
-- (first request rolled back) - never a double-processed request.
CREATE TABLE idempotency_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scope           VARCHAR(50) NOT NULL,
    key_value       VARCHAR(255) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS','COMPLETED')),
    -- Plain TEXT, not JSONB: this is an opaque serialized response replayed verbatim on a
    -- retry, never queried into, so JSONB's structural indexing/query support buys nothing.
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_keys_scope_key UNIQUE (scope, key_value)
);
