package com.interviewscheduler.interview;

import java.time.LocalDate;
import java.time.LocalTime;

// Feeds the reusable rescheduling engine (Phase 14). All fields optional: an omitted window
// means "search from now using the round's original duration/timezone".
public record RescheduleRequest(
        String reason,
        LocalDate dateFrom,
        LocalDate dateTo,
        LocalTime preferredTimeStart,
        LocalTime preferredTimeEnd
) {
}
