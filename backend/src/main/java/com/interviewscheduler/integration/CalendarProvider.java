package com.interviewscheduler.integration;

import com.interviewscheduler.common.exception.CalendarIntegrationException;

/**
 * Pluggable calendar backend. Implementations must be Spring beans; exactly one is active at a
 * time (selected via the {@code calendar.provider} property). The active implementation is
 * injected into {@link CalendarSyncService}, which handles DB state updates and error recovery —
 * implementations here only need to make the provider call and return/throw.
 *
 * <p>All methods may throw {@link CalendarIntegrationException} on provider failure. Callers
 * (i.e., {@link CalendarSyncService}) catch it and mark the DB record FAILED rather than
 * rolling back the booking transaction.
 */
public interface CalendarProvider {

    /**
     * Creates a new calendar event at the provider and returns the provider's event ID and
     * meeting link (e.g., Google Meet). Throws {@link CalendarIntegrationException} on failure.
     */
    ExternalEventResult createEvent(CalendarEventRequest request);

    /**
     * Updates an existing event identified by {@code externalEventId}. Used when an interview
     * is rescheduled and the provider-side event should reflect the new time.
     */
    ExternalEventResult updateEvent(String externalEventId, CalendarEventRequest request);

    /**
     * Cancels (deletes) an existing event at the provider. Implementations should treat a
     * not-found response (event already deleted) as success rather than an error.
     */
    void cancelEvent(String externalEventId);

    /**
     * Reads back the current provider-side state of an event — Phase 21's calendar-webhook
     * reconciliation uses this to detect drift (deleted, time moved, an attendee declined)
     * since Google's push notification carries no event data, only "something changed."
     * Returns {@link CalendarEventSnapshot#notFound()} if the event no longer exists at the
     * provider (treated as success, not an error — mirrors {@link #cancelEvent}'s 404 contract).
     */
    CalendarEventSnapshot getEvent(String externalEventId);

    /** Short identifier written to {@link CalendarEvent#setProvider}. E.g. "GOOGLE", "NOOP". */
    String providerName();
}
