package com.interviewscheduler.scheduling;

import com.interviewscheduler.interview.ParticipantRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CheckConflictsRequest(
        @NotNull UUID roundId,
        @NotNull OffsetDateTime start,
        @NotNull OffsetDateTime end,
        UUID interviewerId,
        @Valid List<RequiredParticipant> requiredParticipants
) {
    /** Covers the recruiter/hiring-manager participants the spec calls out beyond candidate/interviewer. */
    public record RequiredParticipant(
            @NotNull UUID userId,
            @NotNull ParticipantRole role
    ) {
    }
}
