package com.interviewscheduler.job;

import java.math.BigDecimal;
import java.util.UUID;

public record JobSkillResponse(
        UUID id,
        UUID skillId,
        String skillName,
        boolean required,
        BigDecimal weight,
        Short minimumProficiency
) {
    public static JobSkillResponse from(JobSkill jobSkill) {
        return new JobSkillResponse(
                jobSkill.getId(),
                jobSkill.getSkill().getId(),
                jobSkill.getSkill().getName(),
                jobSkill.isRequired(),
                jobSkill.getWeight(),
                jobSkill.getMinimumProficiency()
        );
    }
}
