CREATE TABLE availability (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    date        DATE NOT NULL,
    start_time  TIME NOT NULL,
    end_time    TIME NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE'
                 CHECK (status IN ('AVAILABLE','UNAVAILABLE')),
    timezone    VARCHAR(50) NOT NULL,
    CONSTRAINT ck_availability_time_order CHECK (end_time > start_time)
);

CREATE INDEX idx_availability_user_id ON availability (user_id);
CREATE INDEX idx_availability_date ON availability (date);
