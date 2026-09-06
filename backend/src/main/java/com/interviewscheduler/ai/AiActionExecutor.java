package com.interviewscheduler.ai;

import com.interviewscheduler.common.exception.InvalidBookingException;
import com.interviewscheduler.interview.InterviewProcessService;
import com.interviewscheduler.interview.InterviewReschedulingService;
import com.interviewscheduler.interview.InterviewRoundResponse;
import com.interviewscheduler.interview.RescheduleRequest;
import com.interviewscheduler.interview.RoundResult;
import com.interviewscheduler.interview.RoundResultRequest;
import com.interviewscheduler.scheduling.BookingRequest;
import com.interviewscheduler.scheduling.BookingResponse;
import com.interviewscheduler.scheduling.InterviewBookingService;
import com.interviewscheduler.scheduling.InterviewerReplacementService;
import com.interviewscheduler.scheduling.SchedulingResponse;
import com.interviewscheduler.scheduling.SwitchInterviewerRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Executes a {@link ProposedAction} for real, via the exact same Java services the plain REST
 * API uses - the AI layer never performs a mutation itself, it only decides *what* to propose
 * (see {@link AiOrchestrationService}); every actual booking/reschedule/cancel/switch/result
 * submission goes through full backend validation (locking, fresh conflict checks, RBAC,
 * idempotency) exactly as if a recruiter had called the corresponding REST endpoint directly.
 * This is the one place Phase 27's "book without backend validation" / "claim success without
 * tool result" constraints are structurally enforced, not just followed by convention: if the
 * proposal was based on stale or hallucinated data, the real service call below rejects it the
 * same way it would reject a stale request from the plain REST API.
 *
 * <p>{@code idempotencyKey} is only meaningfully used for {@code BOOK_INTERVIEW} and
 * {@code SWITCH_INTERVIEWER} (the two underlying services that actually accept one, per Phase
 * 12). {@code RESCHEDULE_INTERVIEW}/{@code CANCEL_INTERVIEW}/{@code ADVANCE_CANDIDATE_TO_NEXT_ROUND}/
 * {@code MARK_CANDIDATE_REJECTED} have no idempotency-key concept in their underlying services
 * (Phase 7 never needed one) - a retried confirm for one of these is still safe, just not
 * literally idempotent: the round's own state-machine guards mean a second attempt either
 * no-ops or fails cleanly (e.g. "already CANCELLED", "already COMPLETED") rather than silently
 * double-applying, which is what actually matters. Verified live: retrying an
 * ADVANCE_CANDIDATE_TO_NEXT_ROUND confirm with the same key after it already completed the
 * round correctly returned 409 ("must be SCHEDULED or IN_PROGRESS") rather than progressing the
 * candidate twice.
 */
@Service
@RequiredArgsConstructor
public class AiActionExecutor {

    private final InterviewBookingService interviewBookingService;
    private final InterviewReschedulingService interviewReschedulingService;
    private final InterviewerReplacementService interviewerReplacementService;
    private final InterviewProcessService interviewProcessService;

    public Object execute(ProposedAction action, String idempotencyKey) {
        return switch (action.type()) {
            case BOOK_INTERVIEW -> book(action, idempotencyKey);
            case RESCHEDULE_INTERVIEW -> reschedule(action);
            case CANCEL_INTERVIEW -> interviewReschedulingService.cancel(action.roundId());
            case SWITCH_INTERVIEWER -> switchInterviewer(action, idempotencyKey);
            case ADVANCE_CANDIDATE_TO_NEXT_ROUND -> advance(action.roundId());
            case MARK_CANDIDATE_REJECTED -> reject(action.roundId());
        };
    }

    private BookingResponse book(ProposedAction action, String idempotencyKey) {
        require(action.interviewerId() != null && action.start() != null && action.end() != null && action.timezone() != null,
                "BOOK_INTERVIEW requires interviewerId, start, end and timezone");
        BookingRequest request = new BookingRequest(action.interviewerId(), action.start(), action.end(),
                action.timezone(), idempotencyKey, List.of());
        return interviewBookingService.book(action.roundId(), request);
    }

    private SchedulingResponse reschedule(ProposedAction action) {
        RescheduleRequest request = new RescheduleRequest(action.reason(), null, null, null, null);
        return interviewReschedulingService.reschedule(action.roundId(), request);
    }

    private BookingResponse switchInterviewer(ProposedAction action, String idempotencyKey) {
        require(action.interviewerId() != null, "SWITCH_INTERVIEWER requires interviewerId");
        SwitchInterviewerRequest request = new SwitchInterviewerRequest(
                action.interviewerId(), action.start(), action.end(), action.timezone(), idempotencyKey);
        return interviewerReplacementService.switchInterviewer(action.roundId(), request);
    }

    /** "Advance to next round" = the candidate passed this round - completes it, then submits PASS. */
    private InterviewRoundResponse advance(UUID roundId) {
        interviewProcessService.completeRound(roundId);
        return interviewProcessService.submitResult(roundId, new RoundResultRequest(RoundResult.PASS));
    }

    /** "Mark rejected" = the candidate failed this round - completes it, then submits FAIL
     *  (Phase 7's cascade then rejects the candidate and invalidates future rounds). */
    private InterviewRoundResponse reject(UUID roundId) {
        interviewProcessService.completeRound(roundId);
        return interviewProcessService.submitResult(roundId, new RoundResultRequest(RoundResult.FAIL));
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new InvalidBookingException(message);
        }
    }
}
