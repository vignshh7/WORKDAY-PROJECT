package com.interviewscheduler.common.exception;

/**
 * Raised when an external calendar provider call fails. Never thrown to make a booking
 * fail outright — booking persists with CalendarEventStatus.FAILED and this exception is
 * for surfacing that failure through admin/monitoring endpoints, not for rolling back a
 * successful DB booking.
 */
public class CalendarIntegrationException extends RuntimeException {
    public CalendarIntegrationException(String message) {
        super(message);
    }

    public CalendarIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
