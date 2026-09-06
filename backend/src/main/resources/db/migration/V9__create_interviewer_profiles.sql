CREATE TABLE interviewer_profiles (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    department               VARCHAR(100),
    designation              VARCHAR(100),
    domain                  VARCHAR(100),
    max_interviews_per_day  SMALLINT NOT NULL DEFAULT 4 CHECK (max_interviews_per_day > 0),
    CONSTRAINT uq_interviewer_profiles_user_id UNIQUE (user_id)
);
