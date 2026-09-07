package com.interviewscheduler.user;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalTime;

@ValidWorkingHours
public record UpdateUserRequest(
        @NotBlank String name,
        @NotBlank String timezone,
        LocalTime workingStart,
        LocalTime workingEnd
) {
}
