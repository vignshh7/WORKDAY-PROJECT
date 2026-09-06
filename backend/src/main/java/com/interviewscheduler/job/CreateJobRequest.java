package com.interviewscheduler.job;

import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
        @NotBlank String title,
        String description,
        String department,
        String domain
) {
}
