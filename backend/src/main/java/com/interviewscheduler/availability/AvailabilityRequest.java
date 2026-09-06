package com.interviewscheduler.availability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

@ValidAvailabilityWindow
public record AvailabilityRequest(
        @NotNull LocalDate date,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull AvailabilityStatus status,
        @NotBlank @ValidTimezone String timezone
) {
}
