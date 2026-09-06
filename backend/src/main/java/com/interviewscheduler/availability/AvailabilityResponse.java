package com.interviewscheduler.availability;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityResponse(
        UUID id,
        UUID userId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        AvailabilityStatus status,
        String timezone
) {
    public static AvailabilityResponse from(Availability availability) {
        return new AvailabilityResponse(
                availability.getId(),
                availability.getUser().getId(),
                availability.getDate(),
                availability.getStartTime(),
                availability.getEndTime(),
                availability.getStatus(),
                availability.getTimezone()
        );
    }
}
