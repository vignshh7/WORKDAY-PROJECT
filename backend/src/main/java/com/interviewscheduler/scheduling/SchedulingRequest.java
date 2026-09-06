package com.interviewscheduler.scheduling;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record SchedulingRequest(
        @NotNull UUID candidateId,
        @NotNull UUID roundId,
        UUID preferredInterviewerId,
        @NotNull @Min(1) Integer durationMinutes,
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo,
        LocalTime preferredTimeStart,
        LocalTime preferredTimeEnd,
        List<DayOfWeek> excludedDays,
        List<UUID> requiredParticipantIds,
        @NotBlank String timezone
) {
}
