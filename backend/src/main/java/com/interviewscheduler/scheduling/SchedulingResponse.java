package com.interviewscheduler.scheduling;

import java.util.List;

// When slots is non-empty, reasonCode is null. When the engine finds nothing bookable,
// slots is empty and reasonCode explains why (a valid 200 outcome, not an error —
// SchedulingException is reserved for booking-time failures, not empty search results).
public record SchedulingResponse(
        List<SlotResponse> slots,
        List<SlotResponse> alternatives,
        SchedulingReasonCode reasonCode
) {
    public static SchedulingResponse of(List<SlotResponse> slots, List<SlotResponse> alternatives) {
        return new SchedulingResponse(slots, alternatives, null);
    }

    public static SchedulingResponse empty(SchedulingReasonCode reasonCode) {
        return new SchedulingResponse(List.of(), List.of(), reasonCode);
    }
}
