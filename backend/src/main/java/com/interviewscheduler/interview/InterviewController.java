package com.interviewscheduler.interview;

import com.interviewscheduler.scheduling.BookingRequest;
import com.interviewscheduler.scheduling.BookingResponse;
import com.interviewscheduler.scheduling.FindReplacementResponse;
import com.interviewscheduler.scheduling.InterviewBookingService;
import com.interviewscheduler.scheduling.InterviewerReplacementService;
import com.interviewscheduler.scheduling.SchedulingResponse;
import com.interviewscheduler.scheduling.SwitchInterviewerRequest;
import com.interviewscheduler.interview.ParticipantDeclineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * "An interview" here means a single interview round (interview_rounds row) — the entity
 * that gets scheduled, completed and resolved. Phases 14+ (rescheduling, cancellation) add
 * more actions under this same /api/interviews/{id} base.
 */
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewProcessService interviewProcessService;
    private final InterviewBookingService interviewBookingService;
    private final InterviewReschedulingService interviewReschedulingService;
    private final InterviewerCancellationService interviewerCancellationService;
    private final InterviewerReplacementService interviewerReplacementService;
    private final ParticipantDeclineService participantDeclineService;

    @PostMapping("/{id}/book")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public BookingResponse book(@PathVariable UUID id, @Valid @RequestBody BookingRequest request) {
        return interviewBookingService.book(id, request);
    }

    /** Staff may complete any round; an INTERVIEWER may only complete a round they're assigned
     *  to — {@link InterviewProcessService#completeRound} enforces the ownership check. */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN', 'INTERVIEWER')")
    public InterviewRoundResponse complete(@PathVariable UUID id,
                                            @RequestBody(required = false) CompleteRoundRequest request) {
        return interviewProcessService.completeRound(id);
    }

    /** Staff may record a result on any round; an INTERVIEWER may only do so for a round
     *  they're assigned to — {@link InterviewProcessService#submitResult} enforces the
     *  ownership check. This is how an interviewer's own PASS clears the way for the
     *  candidate's next round to be scheduled. */
    @PostMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN', 'INTERVIEWER')")
    public InterviewRoundResponse submitResult(@PathVariable UUID id, @Valid @RequestBody RoundResultRequest request) {
        return interviewProcessService.submitResult(id, request);
    }

    @PostMapping("/{id}/reschedule")
    public SchedulingResponse reschedule(@PathVariable UUID id,
                                          @RequestBody(required = false) RescheduleRequest request) {
        return interviewReschedulingService.reschedule(id, request == null
                ? new RescheduleRequest(null, null, null, null, null) : request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public InterviewRoundResponse cancel(@PathVariable UUID id) {
        return interviewReschedulingService.cancel(id);
    }

    /**
     * Phase 15: interviewer-initiated cancellation. Only the assigned INTERVIEWER may call
     * this. Returns replacement slot recommendations so the recruiter can immediately confirm
     * a new booking via POST /api/interviews/{id}/book.
     */
    @PostMapping("/{id}/interviewer-cancel")
    @PreAuthorize("hasRole('INTERVIEWER')")
    public SchedulingResponse interviewerCancel(@PathVariable UUID id) {
        return interviewerCancellationService.cancelByInterviewer(id);
    }

    /**
     * Phase 16: find qualified replacement interviewers, ranked by priority
     * (BACKUP_SAME_TIME → QUALIFIED_SAME_TIME → QUALIFIED_OTHER_TIME → ANY_FUTURE_SLOT).
     */
    @PostMapping("/{id}/find-replacement")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public FindReplacementResponse findReplacement(@PathVariable UUID id) {
        return interviewerReplacementService.findReplacement(id);
    }

    /**
     * Phase 16: switch the assigned interviewer. Accepts either the same time slot
     * (same-time swap) or new start/end times (reschedule + swap). Increments reschedule count.
     */
    @PostMapping("/{id}/switch-interviewer")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public BookingResponse switchInterviewer(@PathVariable UUID id,
                                              @Valid @RequestBody SwitchInterviewerRequest request) {
        return interviewerReplacementService.switchInterviewer(id, request);
    }

    /**
     * Phase 17: interviewer formally declines their assignment. For SCHEDULED rounds the round
     * moves to RESCHEDULE_REQUIRED and replacement slots are returned. For PENDING rounds the
     * participant is marked DECLINED with no automatic rescheduling.
     */
    @PostMapping("/{id}/decline")
    @PreAuthorize("hasRole('INTERVIEWER')")
    public SchedulingResponse decline(@PathVariable UUID id) {
        return participantDeclineService.declineByInterviewer(id);
    }

    /**
     * Phase 17: candidate cancels their round. Round → CANCELLED, candidate stage unchanged,
     * no pipeline progression, no automatic reschedule. Rejected if interview is IN_PROGRESS.
     */
    @PostMapping("/{id}/candidate-cancel")
    @PreAuthorize("hasRole('CANDIDATE')")
    public InterviewRoundResponse candidateCancel(@PathVariable UUID id) {
        return participantDeclineService.cancelByCandidate(id);
    }
}
