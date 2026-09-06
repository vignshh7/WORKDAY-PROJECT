CREATE TABLE job_skills (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id                  UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    skill_id                UUID NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    required                BOOLEAN NOT NULL DEFAULT true,
    weight                  NUMERIC(3,2) NOT NULL DEFAULT 1.0 CHECK (weight >= 0),
    minimum_proficiency     SMALLINT NOT NULL CHECK (minimum_proficiency BETWEEN 1 AND 5),
    CONSTRAINT uq_job_skills UNIQUE (job_id, skill_id)
);

CREATE INDEX idx_job_skills_job_id ON job_skills (job_id);
