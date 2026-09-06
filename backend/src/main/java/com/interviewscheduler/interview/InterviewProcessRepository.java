package com.interviewscheduler.interview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewProcessRepository extends JpaRepository<InterviewProcess, UUID> {

    List<InterviewProcess> findByCandidateId(UUID candidateId);

    Optional<InterviewProcess> findByCandidateIdAndStatus(UUID candidateId, ProcessStatus status);

    List<InterviewProcess> findByJobId(UUID jobId);
}
