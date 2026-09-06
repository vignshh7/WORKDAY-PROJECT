package com.interviewscheduler.interviewer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InterviewerProfileRepository extends JpaRepository<InterviewerProfile, UUID> {

    Optional<InterviewerProfile> findByUserId(UUID userId);
}
