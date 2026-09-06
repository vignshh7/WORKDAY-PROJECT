package com.interviewscheduler.candidate;

import java.math.BigDecimal;
import java.util.UUID;

public record CandidateSkillResponse(
        UUID id,
        UUID skillId,
        String skillName,
        Short proficiency,
        BigDecimal yearsExperience
) {
    public static CandidateSkillResponse from(CandidateSkill candidateSkill) {
        return new CandidateSkillResponse(
                candidateSkill.getId(),
                candidateSkill.getSkill().getId(),
                candidateSkill.getSkill().getName(),
                candidateSkill.getProficiency(),
                candidateSkill.getYearsExperience()
        );
    }
}
