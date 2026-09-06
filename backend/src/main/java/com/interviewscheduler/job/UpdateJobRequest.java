package com.interviewscheduler.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateJobRequest(
        @NotBlank String title,
        String description,
        String department,
        String domain,
        @NotNull JobStatus status
) {
}
