package com.interviewscheduler.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The recruiter's explicit confirmation of a {@link ProposedAction} previously returned by
 * {@code POST /api/ai/schedule}. {@code idempotencyKey} is required for the same reason Phase 12
 * requires one on {@code POST /api/interviews/{id}/book} - a retried confirm (double click,
 * network retry) must not double-book/double-switch. Which of {@code action}'s fields are
 * actually required depends on its {@code type} (e.g. only {@code BOOK_INTERVIEW} needs an
 * interviewer/time) - {@link AiActionExecutor} checks that per type, since a single Bean
 * Validation annotation can't express "required depending on another field's value."
 */
public record AiConfirmRequest(
        @NotNull ProposedAction action,
        @NotBlank String idempotencyKey
) {
}
