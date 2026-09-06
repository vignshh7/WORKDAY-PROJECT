package com.interviewscheduler.scheduling;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ReplacementOption(
        UUID interviewerId,
        String interviewerName,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        ReplacementPriority priority,
        double score,
        String reason
) {
}
