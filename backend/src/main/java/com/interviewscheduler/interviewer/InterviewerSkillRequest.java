package com.interviewscheduler.interviewer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record InterviewerSkillRequest(
        @NotNull UUID skillId,
        @NotNull @Min(1) @Max(5) Short proficiency,
        @DecimalMin("0.0") BigDecimal yearsExperience,
        boolean isPrimary
) {
}
