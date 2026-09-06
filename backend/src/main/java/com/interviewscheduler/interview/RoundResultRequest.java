package com.interviewscheduler.interview;

import jakarta.validation.constraints.NotNull;

public record RoundResultRequest(
        @NotNull RoundResult result
) {
}
