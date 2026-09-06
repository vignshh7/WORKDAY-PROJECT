CREATE TABLE candidates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    phone               VARCHAR(30),
    resume_url          TEXT,
    current_status      VARCHAR(20) NOT NULL DEFAULT 'APPLIED'
                         CHECK (current_status IN
                            ('APPLIED','SCREENING','TECHNICAL','MANAGERIAL','HR',
                             'SELECTED','REJECTED','WITHDRAWN','ON_HOLD')),
    -- FK to interview_rounds is added in V8, once that table exists.
    current_round_id    UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_candidates_user_id UNIQUE (user_id)
);

CREATE INDEX idx_candidates_current_status ON candidates (current_status);
