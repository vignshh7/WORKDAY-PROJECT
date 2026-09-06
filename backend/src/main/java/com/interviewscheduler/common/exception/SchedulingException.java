package com.interviewscheduler.common.exception;

/**
 * Raised when the deterministic scheduling engine cannot produce a valid outcome
 * (e.g. no common slot). {@code reasonCode} matches one of the engine's reason codes
 * (NO_CANDIDATE_AVAILABILITY, NO_COMMON_SLOT, ...) so API clients can branch on it.
 */
public class SchedulingException extends RuntimeException {

    private final String reasonCode;

    public SchedulingException(String message, String reasonCode) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String getReasonCode() {
        return reasonCode;
    }
}
