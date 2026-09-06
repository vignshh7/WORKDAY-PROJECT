package com.interviewscheduler.interviewer;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record MatchInterviewersRequest(
        @NotNull UUID roundId
) {
}
