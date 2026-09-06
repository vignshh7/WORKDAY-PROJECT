package com.interviewscheduler.candidate;

public record UpdateCandidateRequest(
        String phone,
        String resumeUrl
) {
}
