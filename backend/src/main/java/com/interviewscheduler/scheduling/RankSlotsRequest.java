package com.interviewscheduler.scheduling;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Ranking is a pure function of (context, candidate slots) - it doesn't re-derive slots
 * itself, so this can rank a set of slots found some other way (not just the ones this
 * service's own find-slots endpoint just produced).
 */
public record RankSlotsRequest(
        @NotNull @Valid SchedulingRequest context,
        @NotEmpty List<SlotResponse> slots
) {
}
