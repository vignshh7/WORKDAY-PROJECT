CREATE TABLE calendar_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_round_id     UUID NOT NULL REFERENCES interview_rounds (id) ON DELETE CASCADE,
    provider                 VARCHAR(50) NOT NULL DEFAULT 'GOOGLE',
    external_event_id        VARCHAR(255),
    meeting_link              TEXT,
    start_time               TIMESTAMPTZ NOT NULL,
    end_time                 TIMESTAMPTZ NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','CREATED','UPDATED','CANCELLED','FAILED')),
    CONSTRAINT ck_calendar_events_time_order CHECK (end_time > start_time)
);

CREATE INDEX idx_calendar_events_round_id ON calendar_events (interview_round_id);
