package com.interviewscheduler.scheduling;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ConflictResponse(
        ConflictType conflictType,
        String message,
        UUID conflictingEntityId,
        OffsetDateTime start,
        OffsetDateTime end
) {
}
