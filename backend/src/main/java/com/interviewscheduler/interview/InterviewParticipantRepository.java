package com.interviewscheduler.interview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewParticipantRepository extends JpaRepository<InterviewParticipant, UUID> {

    List<InterviewParticipant> findByInterviewRoundId(UUID interviewRoundId);

    Optional<InterviewParticipant> findByInterviewRoundIdAndUserId(UUID interviewRoundId, UUID userId);

    List<InterviewParticipant> findByUserIdAndStatusNot(UUID userId, ParticipantStatus status);
}
