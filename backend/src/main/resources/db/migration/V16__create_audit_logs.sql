CREATE TABLE audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Nullable: some audit events are raised by the system or the future AI layer, not a user.
    actor_id        UUID REFERENCES users (id) ON DELETE SET NULL,
    actor_type      VARCHAR(20) NOT NULL DEFAULT 'USER' CHECK (actor_type IN ('USER','SYSTEM','AI')),
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(50) NOT NULL,
    entity_id       UUID,
    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);
CREATE INDEX idx_audit_logs_created_at ON audit_logs (created_at);
