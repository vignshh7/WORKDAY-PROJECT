-- Backs "respect maximum_reschedules" (Phase 14): the reusable rescheduling engine increments
-- this every time it moves a round to RESCHEDULE_REQUIRED, and refuses once it reaches
-- scheduling_config.maximum_reschedules.
ALTER TABLE interview_rounds
    ADD COLUMN reschedule_count SMALLINT NOT NULL DEFAULT 0 CHECK (reschedule_count >= 0);
