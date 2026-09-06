package com.interviewscheduler.integration;

/**
 * Returned by {@link CalendarProvider} after a successful create or update.
 * The {@code externalEventId} is stored in {@link CalendarEvent} so future updates and
 * cancellations can reference the same provider-side record.
 */
public record ExternalEventResult(String externalEventId, String meetingLink) {}
