package com.interviewscheduler.user;

import jakarta.validation.constraints.NotNull;

// Backs PATCH /api/users/{id}/status (Phase 6) — not in the original Phase 4 DTO list,
// but that endpoint needs a request body and this is the minimal shape for it.
public record UpdateUserStatusRequest(
        @NotNull UserStatus status
) {
}
