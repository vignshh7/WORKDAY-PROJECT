-- Phase 20: stores the single org-wide Google Calendar OAuth connection (one connected
-- account whose "primary" calendar every interview event is created on/removed from -
-- matching GoogleCalendarProvider's existing "primary" calendar design from Phase 19).
-- Not one row per user: GoogleOAuthTokenService always updates the most recent row in
-- place on reconnect rather than accumulating rows, so a UNIQUE constraint here would add
-- nothing a service-level "update the latest row" policy doesn't already guarantee.
CREATE TABLE google_oauth_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    access_token TEXT NOT NULL,
    refresh_token TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    scope VARCHAR(500) NOT NULL,
    connected_by_user_id UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
