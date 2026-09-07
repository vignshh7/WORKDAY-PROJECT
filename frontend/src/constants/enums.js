// Every value set here is copied verbatim from backend/API_DOCUMENTATION.md.
// The frontend never invents a status or decides a transition — it only renders what
// the backend returns and labels it.

export const ROLES = ['ADMIN', 'RECRUITER', 'INTERVIEWER', 'CANDIDATE'];

// ADMIN is deliberately absent from the signup picker: the backend does not gate
// ADMIN registration, so we gate it client-side (see frontend_prompt.txt, Phase 31).
export const SIGNUP_ROLES = ['RECRUITER', 'INTERVIEWER', 'CANDIDATE'];

export const USER_STATUSES = ['ACTIVE', 'INACTIVE', 'SUSPENDED'];

export const CANDIDATE_STATUSES = [
  'APPLIED', 'SCREENING', 'TECHNICAL', 'MANAGERIAL', 'HR',
  'SELECTED', 'REJECTED', 'WITHDRAWN', 'ON_HOLD',
];

// Terminal candidate states — no scheduling actions should be offered.
export const CANDIDATE_TERMINAL_STATUSES = ['REJECTED', 'WITHDRAWN', 'SELECTED'];

export const ROUND_STATUSES = [
  'PENDING', 'SCHEDULING', 'SCHEDULED', 'IN_PROGRESS',
  'COMPLETED', 'RESCHEDULE_REQUIRED', 'CANCELLED',
];

export const ROUND_RESULTS = ['PENDING', 'PASS', 'FAIL', 'HOLD'];
export const ROUND_TYPES = ['SCREENING', 'TECHNICAL', 'MANAGERIAL', 'HR'];
export const JOB_STATUSES = ['DRAFT', 'OPEN', 'CLOSED', 'ON_HOLD'];
export const PROCESS_STATUSES = ['ACTIVE', 'COMPLETED', 'REJECTED', 'CANCELLED'];

export const WEEKDAYS = [
  'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY',
];

// Empty-slot explanations from SchedulingResponse.reasonCode. The whole point of the
// reason code is to be specific, so each gets its own message rather than a generic one.
export const REASON_CODE_MESSAGES = {
  NO_CANDIDATE_AVAILABILITY: {
    title: 'The candidate has no availability in this range',
    detail:
      "They have not set any available windows, and have not connected Google Calendar. Ask them to connect their calendar (Settings \u2192 Integrations) or add availability, then search again.",
  },
  NO_INTERVIEWER_AVAILABILITY: {
    title: 'No qualified interviewer is available in this range',
    detail:
      'Interviewers matched the required skills, but none had a free window inside the dates you asked for. Try widening the date range or relaxing the preferred time.',
  },
  NO_COMMON_SLOT: {
    title: 'No overlapping time between the candidate and any interviewer',
    detail:
      'Both sides have availability, but it never overlaps for the full duration. Try a shorter duration or a wider date range.',
  },
  NO_QUALIFIED_INTERVIEWER: {
    title: 'No interviewer qualifies for this round',
    detail:
      "Nobody meets the job's required skills at the minimum proficiency. Use \u201cWhy was someone excluded?\u201d on the match view to see the exact failure reasons.",
  },
  ALL_SLOTS_CONFLICTED: {
    title: 'Every candidate slot hit a conflict',
    detail:
      'Slots existed, but each one collided with an existing booking, buffer, or notice-period rule. Try a different week.',
  },
  OUTSIDE_WORKING_HOURS: {
    title: 'The requested window falls outside working hours',
    detail:
      "The preferred time you asked for is outside the organization's configured working hours. Widen the preferred time or leave it blank.",
  },
  DATE_RANGE_EXHAUSTED: {
    title: 'The date range was fully searched with no result',
    detail: 'Every day in the range was checked. Extend the range and try again.',
  },
};

export const CONFLICT_TYPE_LABELS = {
  CANDIDATE_CONFLICT: 'Candidate is busy',
  INTERVIEWER_CONFLICT: 'Interviewer is busy',
  RECRUITER_CONFLICT: 'Recruiter is busy',
  HIRING_MANAGER_CONFLICT: 'Hiring manager is busy',
  CALENDAR_CONFLICT: 'Google Calendar conflict',
  WORKING_HOURS_CONFLICT: 'Outside working hours',
  BUFFER_CONFLICT: 'Too close to another interview',
  NOTICE_PERIOD_CONFLICT: 'Inside the minimum notice period',
  INVALID_TIME_RANGE: 'Invalid time range',
  ROUND_DEPENDENCY_CONFLICT: 'A previous round must finish first',
};

// NotificationResponse.type -> icon + human sentence.
export const NOTIFICATION_META = {
  INTERVIEW_SCHEDULED: { icon: '\u{1F4C5}', label: 'Interview scheduled' },
  INTERVIEW_RESCHEDULED: { icon: '\u{1F504}', label: 'Interview rescheduled' },
  INTERVIEW_CANCELLED: { icon: '\u{1F6AB}', label: 'Interview cancelled' },
  INTERVIEWER_CANCELLED: { icon: '\u26A0\uFE0F', label: 'Interviewer cancelled \u2014 needs rescheduling' },
  INTERVIEWER_REPLACED: { icon: '\u{1F501}', label: 'Interviewer replaced' },
  INTERVIEW_INVITATION_DECLINED: { icon: '\u2716\uFE0F', label: 'Interview invitation declined' },
  INTERVIEW_REMINDER_24H: { icon: '\u23F0', label: 'Reminder \u2014 interview in 24 hours' },
  INTERVIEW_REMINDER_1H: { icon: '\u23F0', label: 'Reminder \u2014 interview in 1 hour' },
};

export const AI_ACTION_LABELS = {
  BOOK_INTERVIEW: 'Book this interview',
  RESCHEDULE_INTERVIEW: 'Reschedule this interview',
  CANCEL_INTERVIEW: 'Cancel this interview',
  SWITCH_INTERVIEWER: 'Switch the interviewer',
  ADVANCE_CANDIDATE_TO_NEXT_ROUND: 'Advance the candidate to the next round',
  MARK_CANDIDATE_REJECTED: 'Mark the candidate rejected',
};
