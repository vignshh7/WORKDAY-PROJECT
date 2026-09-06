CREATE TABLE interview_rounds (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    process_id              UUID NOT NULL REFERENCES interview_processes (id) ON DELETE CASCADE,
    round_number            SMALLINT NOT NULL,
    round_type              VARCHAR(20) NOT NULL
                             CHECK (round_type IN ('SCREENING','TECHNICAL','MANAGERIAL','HR')),
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN
                                ('PENDING','SCHEDULING','SCHEDULED','IN_PROGRESS',
                                 'COMPLETED','RESCHEDULE_REQUIRED','CANCELLED')),
    result                  VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                             CHECK (result IN ('PENDING','PASS','FAIL','HOLD')),
    duration_minutes        INT NOT NULL DEFAULT 60 CHECK (duration_minutes > 0),
    buffer_minutes          INT NOT NULL DEFAULT 15 CHECK (buffer_minutes >= 0),
    scheduled_start         TIMESTAMPTZ,
    scheduled_end           TIMESTAMPTZ,
    timezone                VARCHAR(50),
    depends_on_round_id     UUID REFERENCES interview_rounds (id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_interview_rounds_process_round UNIQUE (process_id, round_number),
    CONSTRAINT ck_interview_rounds_schedule_order
        CHECK (scheduled_start IS NULL OR scheduled_end IS NULL OR scheduled_end > scheduled_start)
);

CREATE INDEX idx_interview_rounds_process_id ON interview_rounds (process_id);
CREATE INDEX idx_interview_rounds_status ON interview_rounds (status);
CREATE INDEX idx_interview_rounds_scheduled_start ON interview_rounds (scheduled_start);

-- candidates.current_round_id can now reference a real interview_rounds row.
ALTER TABLE candidates
    ADD CONSTRAINT fk_candidates_current_round
        FOREIGN KEY (current_round_id) REFERENCES interview_rounds (id) ON DELETE SET NULL;
