package com.interviewscheduler.candidate;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateResponse(
        UUID id,
        UUID userId,
        String name,
        String email,
        String phone,
        String resumeUrl,
        CandidateStatus currentStatus,
        UUID currentRoundId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CandidateResponse from(Candidate candidate) {
        return new CandidateResponse(
                candidate.getId(),
                candidate.getUser().getId(),
                candidate.getUser().getName(),
                candidate.getUser().getEmail(),
                candidate.getPhone(),
                candidate.getResumeUrl(),
                candidate.getCurrentStatus(),
                candidate.getCurrentRound() == null ? null : candidate.getCurrentRound().getId(),
                candidate.getCreatedAt(),
                candidate.getUpdatedAt()
        );
    }
}
