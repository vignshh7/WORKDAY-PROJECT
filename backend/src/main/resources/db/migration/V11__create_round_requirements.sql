CREATE TABLE round_requirements (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    round_id                UUID NOT NULL REFERENCES interview_rounds (id) ON DELETE CASCADE,
    skill_id                UUID NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    minimum_proficiency     SMALLINT NOT NULL CHECK (minimum_proficiency BETWEEN 1 AND 5),
    required                BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uq_round_requirements UNIQUE (round_id, skill_id)
);

CREATE INDEX idx_round_requirements_round_id ON round_requirements (round_id);
