package com.interviewscheduler.scheduling;

import com.interviewscheduler.admin.ReplacementPolicy;
import com.interviewscheduler.interview.RoundStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record FindReplacementResponse(
        UUID roundId,
        RoundStatus roundStatus,
        OffsetDateTime currentStart,
        OffsetDateTime currentEnd,
        UUID currentInterviewerId,
        String currentInterviewerName,
        ReplacementPolicy policy,
        List<ReplacementOption> options
) {
}
