CREATE TABLE scheduling_config (
    id                                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    working_start                       TIME NOT NULL DEFAULT '09:00',
    working_end                         TIME NOT NULL DEFAULT '18:00',
    default_buffer_minutes              INT NOT NULL DEFAULT 15 CHECK (default_buffer_minutes >= 0),
    minimum_booking_notice_minutes      INT NOT NULL DEFAULT 60 CHECK (minimum_booking_notice_minutes >= 0),
    maximum_scheduling_days             INT NOT NULL DEFAULT 30 CHECK (maximum_scheduling_days > 0),
    allow_weekends                      BOOLEAN NOT NULL DEFAULT false,
    maximum_reschedules                 INT NOT NULL DEFAULT 3 CHECK (maximum_reschedules >= 0),
    interviewer_replacement_policy      VARCHAR(30) NOT NULL DEFAULT 'ASK_BEFORE_REPLACEMENT'
                                         CHECK (interviewer_replacement_policy IN
                                            ('ASK_BEFORE_REPLACEMENT','AUTO_SWITCH_IF_QUALIFIED')),
    updated_at                          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_scheduling_config_hours CHECK (working_end > working_start)
);

-- The scheduling engine always reads a single active configuration row.
INSERT INTO scheduling_config (working_start, working_end)
VALUES ('09:00', '18:00');
