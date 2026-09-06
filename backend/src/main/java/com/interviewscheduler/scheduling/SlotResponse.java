package com.interviewscheduler.scheduling;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SlotResponse(
        UUID interviewerId,
        String interviewerName,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        double score,
        String reason
) {
}
