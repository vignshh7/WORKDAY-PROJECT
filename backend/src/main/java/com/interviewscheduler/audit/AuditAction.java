package com.interviewscheduler.audit;

/**
 * Not a DB CHECK constraint (the column is a plain VARCHAR) so new actions can be added
 * here without a migration — this enum exists for compile-time safety at the call sites.
 */
public enum AuditAction {
    USER_CREATED,
    USER_STATUS_CHANGED,
    CANDIDATE_CREATED,
    JOB_CREATED,
    INTERVIEW_PROCESS_CREATED,
    INTERVIEW_SCHEDULED,
    INTERVIEW_RESCHEDULED,
    INTERVIEW_CANCELLED,
    INTERVIEWER_CANCELLED,
    INTERVIEWER_DECLINED,
    CANDIDATE_INTERVIEW_CANCELLED,
    INTERVIEWER_REPLACED,
    REPLACEMENT_FOUND,
    RESCHEDULING_SLOT_FOUND,
    ROUND_COMPLETED,
    ROUND_RESULT_UPDATED,
    CANDIDATE_STATUS_CHANGED,
    CALENDAR_SYNC_FAILED,
    NOTIFICATION_FAILED
}
