package com.interviewscheduler.integration;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Current provider-side state of one event, as read back by {@link CalendarProvider#getEvent}.
 * Used by {@link CalendarReconciliationService} to detect drift between what we booked and what
 * the external calendar now shows (Phase 21).
 */
public record CalendarEventSnapshot(
        boolean exists,
        OffsetDateTime start,
        OffsetDateTime end,
        List<String> declinedAttendeeEmails
) {
    public static CalendarEventSnapshot notFound() {
        return new CalendarEventSnapshot(false, null, null, List.of());
    }
}
