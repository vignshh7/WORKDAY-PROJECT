-- Phase 21: idempotency log for Google Calendar push notifications. Google retries a webhook
-- delivery on anything but a 2xx response and may also redeliver during normal operation, so
-- (channel_id, message_number) is the dedup key that prevents processing the same notification
-- twice / a webhook-triggered reschedule loop.
CREATE TABLE calendar_webhook_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_id VARCHAR(200) NOT NULL,
    resource_id VARCHAR(200) NOT NULL,
    message_number BIGINT NOT NULL,
    resource_state VARCHAR(50) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (channel_id, message_number)
);
