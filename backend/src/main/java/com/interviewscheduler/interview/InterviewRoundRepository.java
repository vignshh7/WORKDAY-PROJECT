package com.interviewscheduler.interview;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewRoundRepository extends JpaRepository<InterviewRound, UUID> {

    List<InterviewRound> findByProcessIdOrderByRoundNumberAsc(UUID processId);

    Optional<InterviewRound> findByProcessIdAndRoundNumber(UUID processId, short roundNumber);

    List<InterviewRound> findByStatus(RoundStatus status);

    /**
     * Rounds where the given user is an active (non-removed) participant and the round
     * is already scheduled with a time window overlapping [start, end) — the basis for
     * fresh conflict checks before booking.
     */
    @Query("""
            select r from InterviewRound r
            join InterviewParticipant p on p.interviewRound = r
            where p.user.id = :userId
              and p.status <> com.interviewscheduler.interview.ParticipantStatus.REMOVED
              and r.status in (com.interviewscheduler.interview.RoundStatus.SCHEDULED,
                                com.interviewscheduler.interview.RoundStatus.IN_PROGRESS)
              and r.scheduledStart < :end
              and r.scheduledEnd > :start
            """)
    List<InterviewRound> findScheduledOverlapsForUser(
            @Param("userId") UUID userId,
            @Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end);
}
