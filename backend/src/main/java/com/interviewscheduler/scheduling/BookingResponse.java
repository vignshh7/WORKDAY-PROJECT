package com.interviewscheduler.scheduling;

import com.interviewscheduler.interview.RoundStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookingResponse(
        UUID interviewRoundId,
        RoundStatus status,
        OffsetDateTime scheduledStart,
        OffsetDateTime scheduledEnd,
        UUID interviewerId,
        UUID calendarEventId,
        String message
) {
}
