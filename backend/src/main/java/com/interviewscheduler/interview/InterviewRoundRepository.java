package com.interviewscheduler.interview;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewRoundRepository extends JpaRepository<InterviewRound, UUID> {

    /**
     * {@code SELECT ... FOR UPDATE} - serializes concurrent booking/reschedule/cancel attempts
     * on the same round. The second transaction blocks here until the first commits or rolls
     * back, then sees the now-current status instead of racing against it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InterviewRound r where r.id = :id")
    Optional<InterviewRound> findByIdForUpdate(@Param("id") UUID id);

    List<InterviewRound> findByProcessIdOrderByRoundNumberAsc(UUID processId);

    Optional<InterviewRound> findByProcessIdAndRoundNumber(UUID processId, short roundNumber);

    List<InterviewRound> findByStatus(RoundStatus status);

    /** Phase 23: rounds whose scheduled time falls in a reminder-due window. */
    List<InterviewRound> findByStatusAndScheduledStartBetween(
            RoundStatus status, OffsetDateTime windowStart, OffsetDateTime windowEnd);

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
