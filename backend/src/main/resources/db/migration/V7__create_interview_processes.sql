CREATE TABLE interview_processes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id    UUID NOT NULL REFERENCES candidates (id) ON DELETE CASCADE,
    job_id          UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE','COMPLETED','REJECTED','CANCELLED')),
    current_round   SMALLINT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_interview_processes_candidate_id ON interview_processes (candidate_id);
CREATE INDEX idx_interview_processes_job_id ON interview_processes (job_id);
