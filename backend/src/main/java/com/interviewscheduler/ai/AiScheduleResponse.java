package com.interviewscheduler.ai;

import com.interviewscheduler.interviewer.InterviewerMatchResult;
import com.interviewscheduler.scheduling.SlotResponse;

import java.util.List;
import java.util.UUID;

/**
 * Phase 27's required structured shape for {@code POST /api/ai/schedule}. Every field here must
 * trace back to an actual tool call result (see {@link AiReadOnlyTools}) - the system prompt in
 * {@link AiOrchestrationService} instructs the model never to invent one, and nothing in this
 * response is ever persisted directly: a non-null {@code action} is only ever executed later, by
 * {@link AiActionExecutor}, which re-validates everything for real.
 */
public record AiScheduleResponse(
        String interpretedRequest,
        CandidateSummary candidate,
        RoundSummary round,
        List<InterviewerMatchResult> eligibleInterviewers,
        List<SlotResponse> recommendedSlots,
        List<SlotResponse> alternatives,
        String reason,
        boolean confirmationRequired,
        ProposedAction action
) {
    public record CandidateSummary(UUID id, String name, String status) {
    }

    public record RoundSummary(UUID id, String roundType, String status) {
    }
}
