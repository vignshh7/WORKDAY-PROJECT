package com.interviewscheduler.interviewer;

import java.util.UUID;

public record InterviewerResponse(
        UUID id,
        UUID userId,
        String name,
        String email,
        String department,
        String designation,
        String domain,
        Short maxInterviewsPerDay
) {
    public static InterviewerResponse from(InterviewerProfile profile) {
        return new InterviewerResponse(
                profile.getId(),
                profile.getUser().getId(),
                profile.getUser().getName(),
                profile.getUser().getEmail(),
                profile.getDepartment(),
                profile.getDesignation(),
                profile.getDomain(),
                profile.getMaxInterviewsPerDay()
        );
    }
}
