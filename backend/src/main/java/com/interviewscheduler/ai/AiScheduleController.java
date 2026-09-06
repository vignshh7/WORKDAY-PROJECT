package com.interviewscheduler.ai;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 26/27. Both endpoints are RECRUITER/ADMIN-only, same as the deterministic scheduling
 * endpoints this layer orchestrates (Phases 11-17) - the AI layer adds a natural-language front
 * end, it doesn't relax who's allowed to schedule.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
public class AiScheduleController {

    private final AiOrchestrationService orchestrationService;
    private final AiActionExecutor actionExecutor;

    /**
     * Interprets a natural-language request, autonomously calling read-only tools, and returns
     * a structured recommendation. Never mutates anything by itself (see
     * {@link AiOrchestrationService}) - a non-null {@code action} in the response requires a
     * separate {@link #confirm} call before it takes effect.
     */
    @PostMapping("/schedule")
    public AiScheduleResponse schedule(@Valid @RequestBody AiScheduleRequest request) {
        return orchestrationService.interpret(request.message());
    }

    /**
     * Executes a previously-proposed {@link ProposedAction} for real, via the same backend
     * services and validation the plain REST scheduling endpoints use - a stale or invalid
     * proposal is rejected here exactly as it would be from a direct REST call (backend
     * refusals are respected and explained via the same {@code GlobalExceptionHandler} every
     * other endpoint uses).
     */
    @PostMapping("/confirm")
    public AiConfirmResponse confirm(@Valid @RequestBody AiConfirmRequest request) {
        Object result = actionExecutor.execute(request.action(), request.idempotencyKey());
        return new AiConfirmResponse(true, request.action().type(), result,
                "Action executed successfully after explicit confirmation.");
    }
}
