import api from './client';

// One thin function per backend endpoint, grouped by controller. No business logic
// lives here — request shapes come straight from backend/API_DOCUMENTATION.md and the
// response is handed back untouched.

const unwrap = (p) => p.then((r) => r.data);

/* ---------------------------------------------------------------- auth ---- */

export const authApi = {
  // {name, email, password, role, timezone} -> UserResponse (201)
  register: (body) => unwrap(api.post('/api/auth/register', body)),
  // {email, password} -> {token, tokenType, userId, email, role}
  login: (body) => unwrap(api.post('/api/auth/login', body)),
};

/* --------------------------------------------------------------- users ---- */

export const usersApi = {
  list: () => unwrap(api.get('/api/users')), // ADMIN
  get: (id) => unwrap(api.get(`/api/users/${id}`)), // self-or-ADMIN
  // {name, timezone, workingStart?, workingEnd?} — the last two are "HH:mm:ss" or null (org default)
  update: (id, body) => unwrap(api.put(`/api/users/${id}`, body)),
  // {status: ACTIVE|INACTIVE|SUSPENDED} — ADMIN
  setStatus: (id, status) => unwrap(api.patch(`/api/users/${id}/status`, { status })),
};

/* ---------------------------------------------------------- candidates ---- */

export const candidatesApi = {
  list: () => unwrap(api.get('/api/candidates')), // RECRUITER/ADMIN
  // Lets a signed-in CANDIDATE resolve their own profile id without the staff-only list().
  mine: () => unwrap(api.get('/api/candidates/me')),
  get: (id) => unwrap(api.get(`/api/candidates/${id}`)),
  create: (body) => unwrap(api.post('/api/candidates', body)), // {userId, phone, resumeUrl}
  update: (id, body) => unwrap(api.put(`/api/candidates/${id}`, body)), // {phone, resumeUrl}
  skills: (id) => unwrap(api.get(`/api/candidates/${id}/skills`)),
  // {skillId, proficiency (1-5), yearsExperience} — 409 on a duplicate skill
  addSkill: (id, body) => unwrap(api.post(`/api/candidates/${id}/skills`, body)),
  // Every process the candidate has ever had, each with all its rounds.
  pipeline: (id) => unwrap(api.get(`/api/candidates/${id}/pipeline`)),
  status: (id) => unwrap(api.get(`/api/candidates/${id}/status`)),
  activeProcess: (id) => unwrap(api.get(`/api/candidates/${id}/interview-process`)),
  // Cancels every non-terminal round and sets the candidate WITHDRAWN.
  withdraw: (id) => unwrap(api.post(`/api/candidates/${id}/withdraw`)),
};

/* ---------------------------------------------------------------- jobs ---- */

export const jobsApi = {
  list: () => unwrap(api.get('/api/jobs')),
  get: (id) => unwrap(api.get(`/api/jobs/${id}`)),
  // {title, description, department, domain} — created as DRAFT
  create: (body) => unwrap(api.post('/api/jobs', body)),
  update: (id, body) => unwrap(api.put(`/api/jobs/${id}`, body)),
  remove: (id) => unwrap(api.delete(`/api/jobs/${id}`)), // 409 if it has any process
  close: (id) => unwrap(api.post(`/api/jobs/${id}/close`)),
  skills: (id) => unwrap(api.get(`/api/jobs/${id}/skills`)),
  // {skillId, required, weight, minimumProficiency} — skillId must already exist in
  // the seeded `skills` table; there is no skill-creation endpoint.
  addSkill: (id, body) => unwrap(api.post(`/api/jobs/${id}/skills`, body)),
};

/* --------------------------------------------------- interview processes ---- */

export const processesApi = {
  // {candidateId, jobId} — creates 4 rounds; 409 if the candidate already has an
  // ACTIVE process.
  create: (body) => unwrap(api.post('/api/interview-processes', body)),
  get: (id) => unwrap(api.get(`/api/interview-processes/${id}`)),
};

/* -------------------------------------------------------- interviewers ---- */

export const interviewersApi = {
  list: () => unwrap(api.get('/api/interviewers')), // RECRUITER/ADMIN
  // Lets a signed-in INTERVIEWER resolve their own profile id without the staff-only list().
  mine: () => unwrap(api.get('/api/interviewers/me')),
  get: (id) => unwrap(api.get(`/api/interviewers/${id}`)),
  // {userId, department, designation, domain, maxInterviewsPerDay} — RECRUITER/ADMIN
  create: (body) => unwrap(api.post('/api/interviewers', body)),
  skills: (id) => unwrap(api.get(`/api/interviewers/${id}/skills`)),
  // {skillId, proficiency, yearsExperience, isPrimary}
  addSkill: (id, body) => unwrap(api.post(`/api/interviewers/${id}/skills`, body)),
  // {roundId} -> {roundId, eligibleInterviewers, ineligibleInterviewers}
  // Optional before /recommend, but the only source of per-interviewer failureReasons.
  match: (roundId) => unwrap(api.post('/api/interviewers/match', { roundId })),
};

/* -------------------------------------------------------- availability ---- */

export const availabilityApi = {
  // Reading someone else's is allowed; writing is always self-only.
  list: (userId) => unwrap(api.get(`/api/availability/${userId}`)),
  // {date, startTime, endTime, status, timezone} — always created for the caller
  create: (body) => unwrap(api.post('/api/availability', body)),
  update: (id, body) => unwrap(api.put(`/api/availability/${id}`, body)),
  remove: (id) => unwrap(api.delete(`/api/availability/${id}`)),
};

/* ----------------------------------------------------------- scheduling ---- */

export const schedulingApi = {
  // All RECRUITER/ADMIN, all read-only — nothing here books anything.
  checkConflicts: (body) => unwrap(api.post('/api/scheduling/check-conflicts', body)),
  findSlots: (body) => unwrap(api.post('/api/scheduling/find-slots', body)),
  rankSlots: (body) => unwrap(api.post('/api/scheduling/rank-slots', body)),
  // find + rank in one call: top 3 as `slots`, the rest as `alternatives`.
  recommend: (body) => unwrap(api.post('/api/scheduling/recommend', body)),
};

/* ----------------------------------------------------------- interviews ---- */

export const interviewsApi = {
  // {round, process, rounds, candidate} — self-or-staff-or-participant. The only way to
  // fetch a single round by id; works for an INTERVIEWER too, unlike scanning candidate lists.
  get: (roundId) => unwrap(api.get(`/api/interviews/${roundId}`)),
  // {interviewerId, start, end, timezone, idempotencyKey, additionalParticipantIds?}
  // Re-validates from scratch server-side; a stale slot comes back as 422.
  book: (roundId, body) => unwrap(api.post(`/api/interviews/${roundId}/book`, body)),
  complete: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/complete`, {})),
  // {result: PASS|FAIL|HOLD} — the only call that moves the candidate's pipeline status.
  result: (roundId, result) => unwrap(api.post(`/api/interviews/${roundId}/result`, { result })),
  // self-or-staff. -> SchedulingResponse with fresh slots; round -> RESCHEDULE_REQUIRED.
  reschedule: (roundId, body) => unwrap(api.post(`/api/interviews/${roundId}/reschedule`, body || {})),
  // Hard cancel, no auto-reschedule (RECRUITER/ADMIN).
  cancel: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/cancel`)),
  // Assigned interviewer only. -> SchedulingResponse; replacement search runs too.
  interviewerCancel: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/interviewer-cancel`)),
  decline: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/decline`)),
  // The round's own candidate. -> InterviewRoundResponse, no pipeline progression.
  candidateCancel: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/candidate-cancel`)),
  // Read-only "browse replacement options" (RECRUITER/ADMIN).
  findReplacement: (roundId) => unwrap(api.post(`/api/interviews/${roundId}/find-replacement`)),
  // {newInterviewerId, start?, end?, timezone?, idempotencyKey} — commits the switch.
  switchInterviewer: (roundId, body) =>
    unwrap(api.post(`/api/interviews/${roundId}/switch-interviewer`, body)),
};

/* --------------------------------------------------------- integrations ---- */

export const integrationsApi = {
  // -> {authorizationUrl} — navigate the browser there.
  authorizeUrl: () => unwrap(api.get('/api/integrations/google-calendar/authorize')),
  // -> {activeProvider, connected, connectedEmail, expiresAt}
  status: () => unwrap(api.get('/api/integrations/google-calendar/status')),
};

/* -------------------------------------------------------- notifications ---- */

export const notificationsApi = {
  // The caller's own notifications, newest first. No read/unread tracking exists.
  list: () => unwrap(api.get('/api/notifications')),
};

/* ------------------------------------------------------------ audit log ---- */

export const auditApi = {
  // ADMIN. Params: actorId, action, entityType, entityId, dateFrom, dateTo, limit.
  list: (params) => unwrap(api.get('/api/admin/audit-logs', { params })),
};

/* ------------------------------------------------------------------- ai ---- */

export const aiApi = {
  // {message} -> {interpretedRequest, candidate, round, eligibleInterviewers,
  //               recommendedSlots, alternatives, reason, confirmationRequired, action}
  // Nothing is persisted: a non-null `action` is only a proposal.
  schedule: (message) => unwrap(api.post('/api/ai/schedule', { message })),
  // The `action` object must be round-tripped verbatim — it can carry fields the UI
  // never renders. -> {success, actionType, result, message}
  confirm: (action, idempotencyKey) =>
    unwrap(api.post('/api/ai/confirm', { action, idempotencyKey })),
};
