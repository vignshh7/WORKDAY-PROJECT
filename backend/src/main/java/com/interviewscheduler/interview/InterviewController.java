package com.interviewscheduler.interview;

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
 * that gets scheduled, completed and resolved. Phases 12+ (booking, rescheduling,
 * cancellation) add more actions under this same /api/interviews/{id} base.
 */
@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewProcessService interviewProcessService;

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
}
