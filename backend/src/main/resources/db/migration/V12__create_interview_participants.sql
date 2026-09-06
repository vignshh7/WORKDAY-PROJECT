CREATE TABLE interview_participants (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_round_id     UUID NOT NULL REFERENCES interview_rounds (id) ON DELETE CASCADE,
    user_id                 UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    participant_role        VARCHAR(20) NOT NULL
                             CHECK (participant_role IN ('CANDIDATE','INTERVIEWER','RECRUITER','HIRING_MANAGER')),
    assignment_type         VARCHAR(20) NOT NULL DEFAULT 'PRIMARY'
                             CHECK (assignment_type IN ('PRIMARY','BACKUP','REPLACEMENT')),
    status                  VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED'
                             CHECK (status IN ('ASSIGNED','CONFIRMED','DECLINED','REMOVED')),
    CONSTRAINT uq_interview_participants UNIQUE (interview_round_id, user_id)
);

CREATE INDEX idx_interview_participants_round_id ON interview_participants (interview_round_id);
