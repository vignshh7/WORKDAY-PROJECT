package com.interviewscheduler.interview;

import com.interviewscheduler.scheduling.BookingRequest;
import com.interviewscheduler.scheduling.BookingResponse;
import com.interviewscheduler.scheduling.InterviewBookingService;
import com.interviewscheduler.scheduling.SchedulingResponse;
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

    @PostMapping("/{id}/book")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public BookingResponse book(@PathVariable UUID id, @Valid @RequestBody BookingRequest request) {
        return interviewBookingService.book(id, request);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public InterviewRoundResponse complete(@PathVariable UUID id,
                                            @RequestBody(required = false) CompleteRoundRequest request) {
        return interviewProcessService.completeRound(id);
    }

    @PostMapping("/{id}/result")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
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
}
