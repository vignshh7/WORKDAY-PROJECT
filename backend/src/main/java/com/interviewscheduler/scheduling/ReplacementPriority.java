package com.interviewscheduler.scheduling;

public enum ReplacementPriority {
    /** Existing BACKUP participant available at the same scheduled time. */
    BACKUP_SAME_TIME,
    /** Other eligible interviewer available at the same scheduled time. */
    QUALIFIED_SAME_TIME,
    /** Eligible interviewer but at a different time. */
    QUALIFIED_OTHER_TIME,
    /** Any future valid slot (no preferred interviewer constraint). */
    ANY_FUTURE_SLOT
}
