package com.interviewscheduler.interviewer;

import java.util.List;
import java.util.UUID;

public record InterviewerMatchResult(
        UUID interviewerId,
        UUID userId,
        String name,
        boolean eligible,
        List<String> failureReasons,
        Double score
) {
}
