package com.interviewscheduler.common.exception;

/**
 * Raised when a notification cannot be delivered. Like CalendarIntegrationException,
 * this must never roll back a successful booking — notifications persist as FAILED and
 * are retried, not surfaced as a booking failure.
 */
public class NotificationException extends RuntimeException {
    public NotificationException(String message) {
        super(message);
    }

    public NotificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
