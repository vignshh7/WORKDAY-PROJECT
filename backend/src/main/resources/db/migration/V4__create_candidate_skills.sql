CREATE TABLE candidate_skills (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id        UUID NOT NULL REFERENCES candidates (id) ON DELETE CASCADE,
    skill_id            UUID NOT NULL REFERENCES skills (id) ON DELETE CASCADE,
    proficiency         SMALLINT NOT NULL CHECK (proficiency BETWEEN 1 AND 5),
    years_experience    NUMERIC(4,1) CHECK (years_experience >= 0),
    CONSTRAINT uq_candidate_skills UNIQUE (candidate_id, skill_id)
);

CREATE INDEX idx_candidate_skills_candidate_id ON candidate_skills (candidate_id);
