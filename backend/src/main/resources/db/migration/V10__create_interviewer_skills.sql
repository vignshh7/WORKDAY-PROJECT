CREATE TABLE interviewer_skills (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interviewer_id      UUID NOT NULL REFERENCES interviewer_profiles (id) ON DELETE CASCADE,
    skill_id            UUID NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    proficiency         SMALLINT NOT NULL CHECK (proficiency BETWEEN 1 AND 5),
    years_experience    NUMERIC(4,1) CHECK (years_experience >= 0),
    is_primary          BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT uq_interviewer_skills UNIQUE (interviewer_id, skill_id)
);

CREATE INDEX idx_interviewer_skills_interviewer_id ON interviewer_skills (interviewer_id);
