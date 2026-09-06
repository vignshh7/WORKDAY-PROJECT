package com.interviewscheduler.scheduling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// Booking always performs a fresh availability/conflict check server-side, even though the
// client is presumably confirming a slot this same engine already recommended — a previously
// recommended slot must never be trusted as still valid (see Phase 12).
public record BookingRequest(
        @NotNull UUID interviewerId,
        @NotNull OffsetDateTime start,
        @NotNull OffsetDateTime end,
        @NotBlank String timezone,
        @NotBlank String idempotencyKey,
        List<UUID> additionalParticipantIds
) {
}
