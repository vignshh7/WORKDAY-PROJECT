package com.interviewscheduler.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Default calendar provider — active when {@code calendar.provider} is {@code noop} or unset.
 * Does not make any external API calls; logs the intended operation and returns synthetic IDs.
 * Used in development, testing, and any environment where a real calendar integration is not
 * configured. All events are considered "successfully" created so callers get
 * {@link CalendarEventStatus#CREATED} rather than FAILED in their DB records.
 */
@Service
@ConditionalOnProperty(name = "calendar.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpCalendarProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(NoOpCalendarProvider.class);

    @Override
    public ExternalEventResult createEvent(CalendarEventRequest request) {
        String syntheticId = "noop-" + UUID.randomUUID();
        log.info("[NOOP] createEvent roundId={} start={} end={} attendees={} → externalId={}",
                request.roundId(), request.start(), request.end(), request.attendeeEmails(), syntheticId);
        return new ExternalEventResult(syntheticId, null);
    }

    @Override
    public ExternalEventResult updateEvent(String externalEventId, CalendarEventRequest request) {
        log.info("[NOOP] updateEvent externalId={} roundId={} start={} end={}",
                externalEventId, request.roundId(), request.start(), request.end());
        return new ExternalEventResult(externalEventId, null);
    }

    @Override
    public void cancelEvent(String externalEventId, UUID calendarOwnerUserId) {
        log.info("[NOOP] cancelEvent externalId={} ownerUserId={}", externalEventId, calendarOwnerUserId);
    }

    /**
     * No external calendar exists to drift from, so this always reports "unchanged" — a
     * {@code null} start/end tells {@link CalendarReconciliationService} there's nothing to
     * compare, rather than falsely claiming the event still exists at exactly the DB's own
     * recorded time.
     */
    @Override
    public CalendarEventSnapshot getEvent(String externalEventId, UUID calendarOwnerUserId) {
        log.debug("[NOOP] getEvent externalId={} ownerUserId={}", externalEventId, calendarOwnerUserId);
        return new CalendarEventSnapshot(true, null, null, List.of());
    }

    /** No external calendar to be busy on, so this user is never reported busy. */
    @Override
    public List<BusyInterval> getBusyIntervals(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        log.debug("[NOOP] getBusyIntervals userId={} start={} end={}", userId, start, end);
        return List.of();
    }

    @Override
    public String providerName() {
        return "NOOP";
    }
}
