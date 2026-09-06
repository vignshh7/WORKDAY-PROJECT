CREATE TABLE skills (
    id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name    VARCHAR(100) NOT NULL,
    domain  VARCHAR(100),
    CONSTRAINT uq_skills_name UNIQUE (name)
);
