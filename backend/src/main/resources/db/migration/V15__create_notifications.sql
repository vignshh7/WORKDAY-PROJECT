CREATE TABLE notifications (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    interview_round_id     UUID REFERENCES interview_rounds (id) ON DELETE CASCADE,
    type                     VARCHAR(50) NOT NULL,
    channel                  VARCHAR(10) NOT NULL CHECK (channel IN ('EMAIL','SMS','IN_APP')),
    status                   VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','SENT','FAILED')),
    sent_at                  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_user_id ON notifications (user_id);
