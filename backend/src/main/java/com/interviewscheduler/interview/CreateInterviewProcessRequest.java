package com.interviewscheduler.interview;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateInterviewProcessRequest(
        @NotNull UUID candidateId,
        @NotNull UUID jobId
) {
}
