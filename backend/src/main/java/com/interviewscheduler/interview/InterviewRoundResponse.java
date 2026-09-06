package com.interviewscheduler.interview;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InterviewRoundResponse(
        UUID id,
        UUID processId,
        Short roundNumber,
        RoundType roundType,
        RoundStatus status,
        RoundResult result,
        Integer durationMinutes,
        Integer bufferMinutes,
        OffsetDateTime scheduledStart,
        OffsetDateTime scheduledEnd,
        String timezone,
        UUID dependsOnRoundId,
        Short rescheduleCount
) {
    public static InterviewRoundResponse from(InterviewRound round) {
        return new InterviewRoundResponse(
                round.getId(),
                round.getProcess().getId(),
                round.getRoundNumber(),
                round.getRoundType(),
                round.getStatus(),
                round.getResult(),
                round.getDurationMinutes(),
                round.getBufferMinutes(),
                round.getScheduledStart(),
                round.getScheduledEnd(),
                round.getTimezone(),
                round.getDependsOnRound() == null ? null : round.getDependsOnRound().getId(),
                round.getRescheduleCount()
        );
    }
}
