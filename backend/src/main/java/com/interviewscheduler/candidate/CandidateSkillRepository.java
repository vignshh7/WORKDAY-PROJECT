package com.interviewscheduler.candidate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CandidateSkillRepository extends JpaRepository<CandidateSkill, UUID> {

    List<CandidateSkill> findByCandidateId(UUID candidateId);

    Optional<CandidateSkill> findByCandidateIdAndSkillId(UUID candidateId, UUID skillId);

    boolean existsByCandidateIdAndSkillId(UUID candidateId, UUID skillId);
}
