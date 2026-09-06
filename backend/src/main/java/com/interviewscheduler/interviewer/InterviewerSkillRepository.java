package com.interviewscheduler.interviewer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InterviewerSkillRepository extends JpaRepository<InterviewerSkill, UUID> {

    List<InterviewerSkill> findByInterviewerId(UUID interviewerId);

    Optional<InterviewerSkill> findByInterviewerIdAndSkillId(UUID interviewerId, UUID skillId);

    boolean existsByInterviewerIdAndSkillId(UUID interviewerId, UUID skillId);
}
