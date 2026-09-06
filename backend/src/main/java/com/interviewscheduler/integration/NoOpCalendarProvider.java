package com.interviewscheduler.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

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
    public void cancelEvent(String externalEventId) {
        log.info("[NOOP] cancelEvent externalId={}", externalEventId);
    }

    @Override
    public String providerName() {
        return "NOOP";
    }
}
