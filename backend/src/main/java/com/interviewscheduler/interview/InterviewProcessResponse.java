package com.interviewscheduler.interview;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InterviewProcessResponse(
        UUID id,
        UUID candidateId,
        UUID jobId,
        ProcessStatus status,
        Short currentRound,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static InterviewProcessResponse from(InterviewProcess process) {
        return new InterviewProcessResponse(
                process.getId(),
                process.getCandidate().getId(),
                process.getJob().getId(),
                process.getStatus(),
                process.getCurrentRound(),
                process.getCreatedAt(),
                process.getUpdatedAt()
        );
    }
}
