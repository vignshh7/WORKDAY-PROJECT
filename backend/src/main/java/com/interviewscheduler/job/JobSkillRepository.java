package com.interviewscheduler.job;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobSkillRepository extends JpaRepository<JobSkill, UUID> {

    List<JobSkill> findByJobId(UUID jobId);

    Optional<JobSkill> findByJobIdAndSkillId(UUID jobId, UUID skillId);

    boolean existsByJobIdAndSkillId(UUID jobId, UUID skillId);
}
