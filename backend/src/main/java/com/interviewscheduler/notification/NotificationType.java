package com.interviewscheduler.notification;

/**
 * Not a DB CHECK constraint (the column is a plain VARCHAR) so new types can be added
 * here without a migration — this enum exists for compile-time safety at the call sites.
 */
public enum NotificationType {
    INTERVIEW_SCHEDULED,
    INTERVIEW_RESCHEDULED,
    INTERVIEW_CANCELLED,
    INTERVIEWER_CANCELLED,
    INTERVIEWER_REPLACED,
    INTERVIEW_INVITATION,
    INTERVIEW_INVITATION_DECLINED,
    INTERVIEW_REMINDER
}
