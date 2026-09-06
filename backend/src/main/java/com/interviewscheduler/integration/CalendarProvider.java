package com.interviewscheduler.integration;

import com.interviewscheduler.common.exception.CalendarIntegrationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pluggable calendar backend. Implementations must be Spring beans; exactly one is active at a
 * time (selected via the {@code calendar.provider} property). The active implementation is
 * injected into {@link CalendarSyncService}, which handles DB state updates and error recovery —
 * implementations here only need to make the provider call and return/throw.
 *
 * <p>All methods may throw {@link CalendarIntegrationException} on provider failure. Callers
 * (i.e., {@link CalendarSyncService}) catch it and mark the DB record FAILED rather than
 * rolling back the booking transaction.
 *
 * <p>Every method that touches a specific calendar takes a {@code calendarOwnerUserId}/
 * {@code userId}: connections are per-user (see {@link GoogleOAuthToken}), so an implementation
 * needs to know whose token to use. {@link #createEvent} gets this from
 * {@link CalendarEventRequest#calendarOwnerUserId()}.
 */
public interface CalendarProvider {

    /**
     * Creates a new calendar event at the provider (on {@link CalendarEventRequest#calendarOwnerUserId()}'s
     * own calendar) and returns the provider's event ID and meeting link (e.g., Google Meet).
     * Throws {@link CalendarIntegrationException} on failure.
     */
    ExternalEventResult createEvent(CalendarEventRequest request);

    /**
     * Updates an existing event identified by {@code externalEventId}. Used when an interview
     * is rescheduled and the provider-side event should reflect the new time.
     */
    ExternalEventResult updateEvent(String externalEventId, CalendarEventRequest request);

    /**
     * Cancels (deletes) an existing event at the provider. {@code calendarOwnerUserId} is the
     * user whose calendar the event was created on (recorded on {@link CalendarEvent} at
     * creation time - it can't be re-derived from the round's current participants, since those
     * may have changed since, e.g. a Phase 16 interviewer switch). Implementations should treat
     * a not-found response (event already deleted) as success rather than an error.
     */
    void cancelEvent(String externalEventId, UUID calendarOwnerUserId);

    /**
     * Reads back the current provider-side state of an event — Phase 21's calendar-webhook
     * reconciliation uses this to detect drift (deleted, time moved, an attendee declined)
     * since Google's push notification carries no event data, only "something changed."
     * Returns {@link CalendarEventSnapshot#notFound()} if the event no longer exists at the
     * provider (treated as success, not an error — mirrors {@link #cancelEvent}'s 404 contract).
     */
    CalendarEventSnapshot getEvent(String externalEventId, UUID calendarOwnerUserId);

    /**
     * Reads the given user's own busy periods between {@code start} and {@code end} from their
     * connected Google Calendar (via freebusy.query) - used during slot finding to overlay real
     * calendar commitments on top of this system's own {@code Availability} records for both the
     * candidate and the interviewer. Returns an empty list if the user hasn't connected Google
     * Calendar or the lookup fails - see {@code CalendarBusyTimeService}, which is the only
     * caller and treats this as a purely additive enhancement, never a hard blocker (the
     * system's own {@code Availability} table remains the source of truth).
     */
    List<BusyInterval> getBusyIntervals(UUID userId, OffsetDateTime start, OffsetDateTime end);

    /** Short identifier written to {@link CalendarEvent#setProvider}. E.g. "GOOGLE", "NOOP". */
    String providerName();
}
