import { Badge } from './ui';
import { humanize } from '../utils/format';
import {
  CANDIDATE_STATUS_TONE, JOB_STATUS_TONE, NOTIFICATION_STATUS_TONE,
  PROCESS_STATUS_TONE, RESULT_TONE, ROUND_STATUS_TONE, USER_STATUS_TONE,
} from '../constants/theme';

// Candidate status and round status are two different fields from two different
// endpoints and must never be conflated. Each gets its own component so a call site
// can't accidentally render one with the other's vocabulary.

export function CandidateStatusBadge({ status }) {
  if (!status) return <Badge tone="slate">Unknown</Badge>;
  return <Badge tone={CANDIDATE_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

export function RoundStatusBadge({ status }) {
  if (!status) return <Badge tone="slate">Unknown</Badge>;
  return <Badge tone={ROUND_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

/** A round's result is separate from its status — PENDING here means "not judged yet". */
export function ResultBadge({ result, hidePending = false }) {
  if (!result || (hidePending && result === 'PENDING')) return null;
  return <Badge tone={RESULT_TONE[result] || 'slate'}>{humanize(result)}</Badge>;
}

export function JobStatusBadge({ status }) {
  return <Badge tone={JOB_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

export function ProcessStatusBadge({ status }) {
  return <Badge tone={PROCESS_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

export function UserStatusBadge({ status }) {
  return <Badge tone={USER_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

export function NotificationStatusBadge({ status }) {
  return <Badge tone={NOTIFICATION_STATUS_TONE[status] || 'slate'}>{humanize(status)}</Badge>;
}

export function RoleBadge({ role }) {
  const tone = { ADMIN: 'violet', RECRUITER: 'blue', INTERVIEWER: 'indigo', CANDIDATE: 'cyan' }[role];
  return <Badge tone={tone || 'slate'}>{humanize(role)}</Badge>;
}

export function RoundTypeBadge({ type }) {
  return <Badge tone="slate">{humanize(type)}</Badge>;
}
