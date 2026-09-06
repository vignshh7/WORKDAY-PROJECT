package com.interviewscheduler.interviewer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface InterviewerProfileRepository extends JpaRepository<InterviewerProfile, UUID> {

    Optional<InterviewerProfile> findByUserId(UUID userId);

    /**
     * {@code SELECT ... FOR UPDATE} - serializes concurrent booking attempts *for this specific
     * interviewer* even across different rounds, which a per-round lock alone wouldn't catch.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InterviewerProfile i where i.id = :id")
    Optional<InterviewerProfile> findByIdForUpdate(@Param("id") UUID id);
}
