package com.interviewscheduler.interviewer;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateInterviewerProfileRequest(
        @NotNull UUID userId,
        String department,
        String designation,
        String domain,
        @Min(1) Short maxInterviewsPerDay
) {
}
