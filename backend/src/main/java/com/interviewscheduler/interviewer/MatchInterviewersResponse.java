package com.interviewscheduler.interviewer;

import java.util.List;
import java.util.UUID;

public record MatchInterviewersResponse(
        UUID roundId,
        List<InterviewerMatchResult> eligibleInterviewers,
        List<InterviewerMatchResult> ineligibleInterviewers
) {
}
