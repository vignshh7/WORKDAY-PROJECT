package com.interviewscheduler.interviewer;

import java.math.BigDecimal;
import java.util.UUID;

public record InterviewerSkillResponse(
        UUID id,
        UUID skillId,
        String skillName,
        Short proficiency,
        BigDecimal yearsExperience,
        boolean isPrimary
) {
    public static InterviewerSkillResponse from(InterviewerSkill interviewerSkill) {
        return new InterviewerSkillResponse(
                interviewerSkill.getId(),
                interviewerSkill.getSkill().getId(),
                interviewerSkill.getSkill().getName(),
                interviewerSkill.getProficiency(),
                interviewerSkill.getYearsExperience(),
                interviewerSkill.isPrimary()
        );
    }
}
