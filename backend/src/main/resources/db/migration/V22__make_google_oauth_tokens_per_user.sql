-- Phase 20 revision: Google Calendar OAuth moves from one org-wide connection (V20) to one
-- connection per user. Each candidate/interviewer (and, self-service, any other role) connects
-- their own calendar; the app looks up a specific user's token by user_id rather than "the most
-- recently connected" row. connected_by_user_id (V20) always equals the resource owner once
-- every connection is per-user, so it's superseded by user_id rather than kept alongside it.
ALTER TABLE google_oauth_tokens ADD COLUMN user_id UUID REFERENCES users(id);
UPDATE google_oauth_tokens SET user_id = connected_by_user_id WHERE user_id IS NULL;
ALTER TABLE google_oauth_tokens ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE google_oauth_tokens ADD CONSTRAINT uq_google_oauth_tokens_user_id UNIQUE (user_id);
ALTER TABLE google_oauth_tokens DROP COLUMN connected_by_user_id;
