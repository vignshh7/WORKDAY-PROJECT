package com.interviewscheduler.scheduling;

import java.util.List;

public record ConflictCheckResponse(
        boolean hasConflicts,
        List<ConflictResponse> conflicts
) {
    public static ConflictCheckResponse of(List<ConflictResponse> conflicts) {
        return new ConflictCheckResponse(!conflicts.isEmpty(), conflicts);
    }
}
