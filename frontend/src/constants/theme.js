// Badge tones per status value. Kept in one place so candidate status and round
// status never accidentally share a visual language they don't share semantically.

export const CANDIDATE_STATUS_TONE = {
  APPLIED: 'slate', SCREENING: 'blue', TECHNICAL: 'indigo', MANAGERIAL: 'violet',
  HR: 'cyan', SELECTED: 'green', REJECTED: 'red', WITHDRAWN: 'slate', ON_HOLD: 'amber',
};

export const ROUND_STATUS_TONE = {
  PENDING: 'slate', SCHEDULING: 'blue', SCHEDULED: 'green', IN_PROGRESS: 'indigo',
  COMPLETED: 'slate', RESCHEDULE_REQUIRED: 'amber', CANCELLED: 'red',
};

export const RESULT_TONE = { PENDING: 'slate', PASS: 'green', FAIL: 'red', HOLD: 'amber' };
export const JOB_STATUS_TONE = { DRAFT: 'slate', OPEN: 'green', CLOSED: 'red', ON_HOLD: 'amber' };
export const PROCESS_STATUS_TONE = { ACTIVE: 'blue', COMPLETED: 'green', REJECTED: 'red', CANCELLED: 'slate' };
export const USER_STATUS_TONE = { ACTIVE: 'green', INACTIVE: 'slate', SUSPENDED: 'red' };
export const NOTIFICATION_STATUS_TONE = { PENDING: 'amber', SENT: 'green', FAILED: 'red' };
