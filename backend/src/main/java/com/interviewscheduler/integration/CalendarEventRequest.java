package com.interviewscheduler.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provider-agnostic data transfer object passed to {@link CalendarProvider} implementations.
 * Assembling the request is done by {@link CalendarSyncService}; providers only consume it.
 *
 * @param calendarOwnerUserId whose Google Calendar the event should be created/updated on (the
 *                            round's assigned interviewer - see {@code CalendarSyncService});
 *                            every other participant is invited by email via
 *                            {@code attendeeEmails} instead, and doesn't need their own
 *                            connection just to receive an invite.
 */
public record CalendarEventRequest(
        UUID roundId,
        UUID calendarOwnerUserId,
        String title,
        String description,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        List<String> attendeeEmails
) {}
