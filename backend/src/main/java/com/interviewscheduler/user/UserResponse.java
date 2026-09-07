package com.interviewscheduler.user;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String name,
        String email,
        Role role,
        UserStatus status,
        String timezone,
        LocalTime workingStart,
        LocalTime workingEnd,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getTimezone(),
                user.getWorkingStart(),
                user.getWorkingEnd(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
