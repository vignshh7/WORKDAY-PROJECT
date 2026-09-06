package com.interviewscheduler.ai;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A consequential operation the AI has proposed but not executed - only {@link AiActionExecutor}
 * (called from an explicit {@code POST /api/ai/confirm}, or automatically for
 * {@code SWITCH_INTERVIEWER} under {@code AUTO_SWITCH_IF_QUALIFIED}) ever turns this into a
 * real backend mutation. Fields are interpreted per {@link AiActionType}: {@code BOOK_INTERVIEW}
 * needs {@code interviewerId}/{@code start}/{@code end}/{@code timezone}; {@code RESCHEDULE_INTERVIEW}
 * and {@code CANCEL_INTERVIEW} only need {@code roundId} ({@code reason} optional for reschedule);
 * {@code SWITCH_INTERVIEWER} needs {@code interviewerId} (the replacement) and optionally a new
 * {@code start}/{@code end}/{@code timezone}; {@code ADVANCE_CANDIDATE_TO_NEXT_ROUND} and
 * {@code MARK_CANDIDATE_REJECTED} only need {@code roundId}.
 */
public record ProposedAction(
        AiActionType type,
        UUID roundId,
        UUID interviewerId,
        OffsetDateTime start,
        OffsetDateTime end,
        String timezone,
        String reason
) {
}
