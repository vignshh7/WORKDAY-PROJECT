package com.interviewscheduler.job;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record JobSkillRequest(
        @NotNull UUID skillId,
        @NotNull Boolean required,
        @DecimalMin("0.0") BigDecimal weight,
        @NotNull @Min(1) @Max(5) Short minimumProficiency
) {
}
