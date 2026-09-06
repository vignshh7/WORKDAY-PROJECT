-- Phase 20 revision: per-user OAuth (V22) means create/update/cancel/getEvent on a calendar
-- event must know which user's Google account it lives on. That can't be re-derived from the
-- round's current participants at cancel/reconciliation time, since those can change after the
-- event was created (e.g. a Phase 16 interviewer switch) - so it's recorded once, at creation,
-- on the event itself. Nullable: NoOp-provider events (and any pre-Phase-20-revision Google
-- events) simply have no recorded owner.
ALTER TABLE calendar_events ADD COLUMN calendar_owner_user_id UUID REFERENCES users(id);
