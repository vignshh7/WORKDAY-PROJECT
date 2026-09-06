package com.interviewscheduler.scheduling;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SwitchInterviewerRequest(
        @NotNull UUID newInterviewerId,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        @NotBlank String idempotencyKey
) {
}
