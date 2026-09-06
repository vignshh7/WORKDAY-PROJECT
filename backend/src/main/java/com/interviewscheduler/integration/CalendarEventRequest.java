package com.interviewscheduler.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Provider-agnostic data transfer object passed to {@link CalendarProvider} implementations.
 * Assembling the request is done by {@link CalendarSyncService}; providers only consume it.
 */
public record CalendarEventRequest(
        UUID roundId,
        String title,
        String description,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        List<String> attendeeEmails
) {}
