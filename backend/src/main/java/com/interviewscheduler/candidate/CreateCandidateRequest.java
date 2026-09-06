package com.interviewscheduler.candidate;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Candidate profiles attach to an already-registered CANDIDATE user (see auth.RegisterRequest);
// this does not create the user account itself.
public record CreateCandidateRequest(
        @NotNull UUID userId,
        String phone,
        String resumeUrl
) {
}
