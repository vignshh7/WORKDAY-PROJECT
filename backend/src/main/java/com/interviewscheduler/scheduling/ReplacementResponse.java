package com.interviewscheduler.scheduling;

import com.interviewscheduler.interview.AssignmentType;

import java.util.List;
import java.util.UUID;

public record ReplacementResponse(
        UUID replacementInterviewerId,
        String replacementName,
        AssignmentType assignmentType,
        List<SlotResponse> recommendedSlots,
        SchedulingReasonCode reasonCode
) {
}
