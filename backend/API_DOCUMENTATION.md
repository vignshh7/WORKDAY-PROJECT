# API Documentation — Interview Scheduler Backend

Phase 29 deliverable. This is the human-readable companion to the live, auto-generated
Swagger/OpenAPI UI (`GET /swagger-ui.html`, raw spec at `GET /v3/api-docs`) — read this for
narrative context and curl examples; read Swagger for the always-up-to-date field-level schema.

Every endpoint here was verified against a real running instance and a real Supabase database
during development (see `README.md`'s per-phase "Verified live" notes) — this document reflects
what actually exists in the code, not the original design spec (`prompt.txt`), which this
backend deviates from in a few places noted inline.

## Contents

- [Authentication](#authentication)
- [Roles & object-level authorization](#roles--object-level-authorization)
- [Error format](#error-format)
- [Users](#users)
- [Candidates](#candidates)
- [Jobs](#jobs)
- [Interview Processes](#interview-processes)
- [Interviewers](#interviewers)
- [Availability](#availability)
- [Scheduling (deterministic engine)](#scheduling-deterministic-engine)
- [Interviews (booking, cancellation, rescheduling, replacement)](#interviews-booking-cancellation-rescheduling-replacement)
- [Google Calendar Integration](#google-calendar-integration)
- [Notifications](#notifications)
- [Audit Logs](#audit-logs)
- [Agentic AI](#agentic-ai)
- [Known gaps](#known-gaps)

---

## Authentication

Every endpoint except the ones listed below requires a JWT:

```
Authorization: Bearer <token>
```

Get a token via:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Jane Recruiter","email":"jane@example.com","password":"Password123!","role":"RECRUITER","timezone":"Asia/Kolkata"}'
# -> 201, UserResponse body (no password hash)

curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"jane@example.com","password":"Password123!"}'
# -> 200, LoginResponse: {"token":"...", "tokenType":"Bearer", "userId":"...", "email":"...", "role":"RECRUITER"}
```

`role` at registration is one of `ADMIN`, `RECRUITER`, `INTERVIEWER`, `CANDIDATE` — there is no
invite/approval flow, registration is self-service for every role (a real deployment would want
to lock down who can register as `ADMIN`; not enforced today).

**Public endpoints** (no JWT needed, from `SecurityConfig`):
- `POST /api/auth/register`, `POST /api/auth/login`
- `GET /api/integrations/google-calendar/callback` — Google redirects the browser here directly
- `POST /api/integrations/google-calendar/webhook` — Google calls this server-to-server
- `GET /actuator/health`, `GET /actuator/info`
- `GET /v3/api-docs/**`, `/swagger-ui/**`

Everything else returns `401` with no token, or an expired/malformed one.

## Roles & object-level authorization

Two layers, both real:
1. **Role gates** — `@PreAuthorize` on the endpoint, checked below per-endpoint. A role mismatch
   is `403`.
2. **Object-level ownership** — some endpoints have no role annotation because *any* role can
   call them, but the service checks the caller against the resource (e.g. a candidate can read
   their own profile, but not another candidate's — also `403`). Called out per-endpoint below
   as "self-or-staff".

## Error format

Every error (validation, auth, not-found, business-rule refusal, unexpected) returns the same
shape:

```json
{
  "timestamp": "2026-09-06T12:00:00+05:30",
  "status": 409,
  "error": "CONFLICT",
  "message": "Round must be SCHEDULED or IN_PROGRESS to complete; current status: COMPLETED",
  "path": "/api/interviews/.../complete"
}
```

| Exception | HTTP status |
|---|---|
| Bean Validation failure (bad request body) | 400 |
| `UnauthorizedException` / missing or bad JWT | 401 |
| `ForbiddenException` / role or ownership check failed | 403 |
| `ResourceNotFoundException` | 404 |
| `DuplicateResourceException`, `InvalidStateTransitionException`, `ConflictException` | 409 |
| `InvalidBookingException`, `SchedulingException` | 422 |
| `CalendarIntegrationException` | 502 |
| `NotificationException` | 500 |
| Anything uncaught | 500 (never a raw stack trace) |

---

## Users

| Endpoint | Role | Notes |
|---|---|---|
| `GET /api/users` | ADMIN | List every user |
| `GET /api/users/{id}` | self-or-ADMIN | `UserResponse` |
| `PUT /api/users/{id}` | self-or-ADMIN | Body: `UpdateUserRequest{name, timezone, workingStart?, workingEnd?}` — the last two must both be set or both left `null` (`null` means "use the org default"); `workingEnd` must be after `workingStart` |
| `PATCH /api/users/{id}/status` | ADMIN | Body: `UpdateUserStatusRequest{status: ACTIVE\|INACTIVE\|SUSPENDED}` |

`UserResponse`: `{id, name, email, role, status, timezone, createdAt, updatedAt}`

## Candidates

| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/candidates` | RECRUITER/ADMIN | Body: `CreateCandidateRequest{userId, phone, resumeUrl}` — attaches a profile to an *already-registered* `CANDIDATE` user |
| `GET /api/candidates` | RECRUITER/ADMIN | List every candidate |
| `GET /api/candidates/me` | CANDIDATE | Resolves the signed-in candidate's own profile — the only way a candidate can discover their own candidate id, since `findAll` is staff-only |
| `GET /api/candidates/{id}` | self-or-staff | |
| `PUT /api/candidates/{id}` | self-or-staff | Body: `UpdateCandidateRequest{phone, resumeUrl}` |
| `GET /api/candidates/{id}/skills` | self-or-staff | -> `List<CandidateSkillResponse>` |
| `POST /api/candidates/{id}/skills` | self-or-staff | Body: `CandidateSkillRequest{skillId, proficiency (1-5), yearsExperience}` — `409` on duplicate skill |
| `GET /api/candidates/{id}/pipeline` | self-or-staff | -> `List<CandidatePipelineEntry>` — every process the candidate has ever had, each with all its rounds |
| `GET /api/candidates/{id}/status` | self-or-staff | -> `{"status": "TECHNICAL"}` |
| `GET /api/candidates/{id}/interview-process` | self-or-staff | The candidate's current *ACTIVE* process only |
| `POST /api/candidates/{id}/withdraw` | self-or-staff | Cancels every non-terminal round, sets candidate `WITHDRAWN` — no body |

`CandidateResponse`: `{id, userId, name, email, phone, resumeUrl, currentStatus, currentRoundId, createdAt, updatedAt}`
`CandidateSkillResponse`: `{id, skillId, skillName, proficiency, yearsExperience}`
`CandidatePipelineEntry`: `{process: InterviewProcessResponse, rounds: [InterviewRoundResponse...]}`

`currentStatus` values: `APPLIED, SCREENING, TECHNICAL, MANAGERIAL, HR, SELECTED, REJECTED, WITHDRAWN, ON_HOLD`.

## Jobs

| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/jobs` | RECRUITER/ADMIN | Body: `CreateJobRequest{title, description, department, domain}` — created as `DRAFT` |
| `GET /api/jobs` | any authenticated user | |
| `GET /api/jobs/{id}` | any authenticated user | |
| `PUT /api/jobs/{id}` | RECRUITER/ADMIN | Body: `UpdateJobRequest{title, description, department, domain, status}` |
| `DELETE /api/jobs/{id}` | RECRUITER/ADMIN | `409` if the job has any interview process (never silently cascades) |
| `POST /api/jobs/{id}/close` | RECRUITER/ADMIN | Sets `CLOSED` — no body |
| `GET /api/jobs/{id}/skills` | any authenticated user | -> `List<JobSkillResponse>` |
| `POST /api/jobs/{id}/skills` | RECRUITER/ADMIN | Body: `JobSkillRequest{skillId, required, weight, minimumProficiency (1-5)}` |

`JobResponse`: `{id, title, description, department, domain, status, createdByUserId, createdAt, updatedAt}`
`JobSkillResponse`: `{id, skillId, skillName, required, weight, minimumProficiency}`
`status` values: `DRAFT, OPEN, CLOSED, ON_HOLD`.

There is no skill-creation endpoint — `skillId` must reference an existing row in the `skills`
table (seeded directly, not via the API).

## Interview Processes

| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/interview-processes` | RECRUITER/ADMIN | Body: `{candidateId, jobId}` — creates 4 rounds (SCREENING→TECHNICAL→MANAGERIAL→HR, in that fixed dependency order) and moves the candidate to `SCREENING`. `409` if the candidate already has an ACTIVE process. |
| `GET /api/interview-processes/{id}` | self-or-staff | |

`InterviewProcessResponse`: `{id, candidateId, jobId, status, currentRound, createdAt, updatedAt}`
(`status`: `ACTIVE, COMPLETED, REJECTED, CANCELLED`; `currentRound` is a round *number*, 1-4)

Round-level actions (`complete`, `result`, `book`, etc.) live under `/api/interviews/{roundId}/...`
— see [Interviews](#interviews-booking-cancellation-rescheduling-replacement) below.

## Interviewers

| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/interviewers` | RECRUITER/ADMIN | Body: `CreateInterviewerProfileRequest{userId, department, designation, domain, maxInterviewsPerDay}` — attaches a profile to an already-registered `INTERVIEWER` user |
| `GET /api/interviewers` | RECRUITER/ADMIN | List every interviewer profile (despite the doc previously here, this is actually staff-only — `InterviewerService.findAll` calls `requireRecruiterOrAdmin`) |
| `GET /api/interviewers/me` | INTERVIEWER | Resolves the signed-in interviewer's own profile — the only way an interviewer can discover their own interviewer id, since `findAll` is staff-only |
| `GET /api/interviewers/{id}` | any authenticated user | |
| `GET /api/interviewers/{id}/skills` | any authenticated user | -> `List<InterviewerSkillResponse>` |
| `POST /api/interviewers/{id}/skills` | any authenticated user | Body: `InterviewerSkillRequest{skillId, proficiency (1-5), yearsExperience, isPrimary}` |
| `POST /api/interviewers/match` | RECRUITER/ADMIN | Body: `{roundId}` -> `MatchInterviewersResponse` |

`InterviewerResponse`: `{id, userId, name, email, department, designation, domain, maxInterviewsPerDay}`
`InterviewerSkillResponse`: `{id, skillId, skillName, proficiency, yearsExperience, isPrimary}`
`MatchInterviewersResponse`: `{roundId, eligibleInterviewers: [InterviewerMatchResult...], ineligibleInterviewers: [InterviewerMatchResult...]}`
`InterviewerMatchResult`: `{interviewerId, userId, name, eligible, failureReasons: [string...], score}`

Matching order: required skill exists → proficiency ≥ minimum → interviewer `ACTIVE`
(disqualifying); domain match, primary-skill bonus, workload are ranking-only, not disqualifying.

## Availability

Every user manages only **their own** availability (no staff-writes-for-someone-else override —
`POST`/`PUT`/`DELETE` are self-only). Staff can still *read* anyone's via `GET /{userId}`.

| Endpoint | Role | Notes |
|---|---|---|
| `POST /api/availability` | any authenticated user | Body: `AvailabilityRequest{date, startTime, endTime, status: AVAILABLE\|UNAVAILABLE, timezone}` — always creates for the caller |
| `GET /api/availability/{userId}` | any authenticated user | Reading someone else's is allowed (a recruiter needs to see it to schedule) |
| `PUT /api/availability/{id}` | owner only | |
| `DELETE /api/availability/{id}` | owner only | |

`AvailabilityResponse`: `{id, userId, date, startTime, endTime, status, timezone}`

`AVAILABLE` windows are validated against `scheduling_config`'s working hours and weekend policy,
and against overlapping with the user's own existing windows (computed on real UTC instants, so
cross-timezone overlaps are caught correctly). `UNAVAILABLE` windows skip those checks (they only
narrow availability, never claim bookability).

**These manual entries only matter for a user who has NOT connected Google Calendar.** Once a
user connects Google (`CALENDAR_PROVIDER=google` and a token exists for them), their availability
for slot-finding is computed instead of declared — see the note at the end of the Scheduling
section below — and their manual `Availability` rows are no longer read for that purpose (the
endpoints above still work normally, the data just stops being consulted by the scheduling
engine for a connected user).

## Scheduling (deterministic engine)

All RECRUITER/ADMIN only. This is the "find a slot" pipeline (Phase 9-11) — read-only, nothing
here books anything.

| Endpoint | Body | Returns |
|---|---|---|
| `POST /api/scheduling/check-conflicts` | `CheckConflictsRequest{roundId, start, end, interviewerId?, requiredParticipants?: [{userId, role}]}` | `ConflictCheckResponse{hasConflicts, conflicts: [ConflictResponse...]}` |
| `POST /api/scheduling/find-slots` | `SchedulingRequest` (see below) | `SchedulingResponse{slots, alternatives, reasonCode}` — raw, unranked |
| `POST /api/scheduling/rank-slots` | `RankSlotsRequest{context: SchedulingRequest, slots: [SlotResponse...]}` | `List<SlotResponse>`, re-sorted |
| `POST /api/scheduling/recommend` | `SchedulingRequest` | `SchedulingResponse` — find + rank in one call, top 3 as `slots`, rest as `alternatives` |

`SchedulingRequest`: `{candidateId, roundId, preferredInterviewerId?, durationMinutes, dateFrom, dateTo, preferredTimeStart?, preferredTimeEnd?, excludedDays?: [MONDAY...], requiredParticipantIds?: [uuid...], timezone}`

`ConflictResponse`: `{conflictType, message, conflictingEntityId, start, end}`. `conflictType` one
of: `CANDIDATE_CONFLICT, INTERVIEWER_CONFLICT, RECRUITER_CONFLICT, HIRING_MANAGER_CONFLICT,
CALENDAR_CONFLICT, WORKING_HOURS_CONFLICT, BUFFER_CONFLICT, NOTICE_PERIOD_CONFLICT,
INVALID_TIME_RANGE, ROUND_DEPENDENCY_CONFLICT`.

When `slots` comes back empty, `reasonCode` explains why — one of: `NO_CANDIDATE_AVAILABILITY,
NO_INTERVIEWER_AVAILABILITY, NO_COMMON_SLOT, NO_QUALIFIED_INTERVIEWER, ALL_SLOTS_CONFLICTED,
OUTSIDE_WORKING_HOURS, DATE_RANGE_EXHAUSTED`. This is a normal `200`, never an exception.

```bash
curl -X POST http://localhost:8080/api/scheduling/recommend \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "candidateId":"...", "roundId":"...", "durationMinutes":60,
    "dateFrom":"2026-09-14", "dateTo":"2026-09-18",
    "preferredTimeStart":"13:00:00", "preferredTimeEnd":"17:00:00",
    "excludedDays":["FRIDAY"], "timezone":"Asia/Kolkata"
  }'
```

**How a participant's "available windows" are computed** (candidate, each eligible interviewer,
and any required participant are all resolved the same way):
- **Connected to Google Calendar** (`CALENDAR_PROVIDER=google` and they've completed
  `/authorize`): availability = their own working hours — `users.working_start`/`working_end`
  if they've set a personal override via `PUT /api/users/{id}`, otherwise the organization's
  configured default (`scheduling_config.working_start`/`working_end`), weekend policy either
  way — expressed in **that user's own stored timezone** (`users.timezone`, set at registration
  or via `PUT /api/users/{id}`), minus whatever Google's `freebusy.query` reports as busy for
  them in the requested date range and minus any of their own other SCHEDULED/IN_PROGRESS
  interview rounds (buffer-expanded). Their manual `Availability` rows are not consulted at all
  in this case.
- **Not connected**: falls back to their manual `Availability` `AVAILABLE` rows exactly as
  before Phase 20 — this is the only case where `POST /api/availability` entries actually feed
  the scheduling engine.

Either way, the result is transparent to the caller — same request/response shape, and the
existing common-window intersection (candidate ∩ interviewer ∩ required participants) applies
identically regardless of which source each individual participant's windows came from. A
Google lookup failure (expired/revoked token, API error) never blocks scheduling — it's treated
as "no extra busy data," same as never having connected.

## Interviews (booking, cancellation, rescheduling, replacement)

All under `/api/interviews/{roundId}/...` — "an interview" here means one `interview_rounds` row.

| Endpoint | Role | Body | Returns |
|---|---|---|---|
| `GET /{id}` | staff, the round's own candidate, or an active participant (e.g. the assigned INTERVIEWER) | — | `InterviewRoundDetailResponse{round, process, rounds, candidate}` — the only way to fetch a single round by id; access is checked inside the service since it depends on the round's own data, not just the caller's role |
| `POST /{id}/book` | RECRUITER/ADMIN, or CANDIDATE (must be the round's own candidate) | `BookingRequest{interviewerId, start, end, timezone, idempotencyKey, additionalParticipantIds?}` | `BookingResponse{interviewRoundId, status, scheduledStart, scheduledEnd, interviewerId, calendarEventId, message}` |
| `POST /{id}/complete` | RECRUITER/ADMIN, or INTERVIEWER (must be the assigned one) | `CompleteRoundRequest` (empty, or omit body) | `InterviewRoundResponse` — moves SCHEDULED/IN_PROGRESS → COMPLETED |
| `POST /{id}/result` | RECRUITER/ADMIN, or INTERVIEWER (must be the assigned one) | `RoundResultRequest{result: PASS\|FAIL\|HOLD}` | `InterviewRoundResponse` — PASS advances the candidate (or SELECTED on the last round) and clears the next round to be scheduled; FAIL rejects the candidate and cancels every future round; HOLD is a no-op on progression |
| `POST /{id}/reschedule` | self-or-staff | `RescheduleRequest{reason?, dateFrom?, dateTo?, preferredTimeStart?, preferredTimeEnd?}` (or omit body) | `SchedulingResponse` — round → `RESCHEDULE_REQUIRED`, then re-runs find+rank immediately, returning new candidate slots |
| `POST /{id}/cancel` | RECRUITER/ADMIN | none | `InterviewRoundResponse` — round → `CANCELLED`, no auto-reschedule |
| `POST /{id}/interviewer-cancel` | INTERVIEWER (must be the assigned one) | none | `SchedulingResponse` — round → `RESCHEDULE_REQUIRED`, replacement search runs automatically |
| `POST /{id}/find-replacement` | RECRUITER/ADMIN | none | `FindReplacementResponse{roundId, roundStatus, currentStart, currentEnd, currentInterviewerId, currentInterviewerName, policy, options: [ReplacementOption...]}` — read-only search |
| `POST /{id}/switch-interviewer` | RECRUITER/ADMIN | `SwitchInterviewerRequest{newInterviewerId, start?, end?, timezone?, idempotencyKey}` | `BookingResponse` — always requires this explicit call; `AUTO_SWITCH_IF_QUALIFIED` policy is only auto-applied by the AI orchestration path (see below), never by this REST endpoint |
| `POST /{id}/decline` | INTERVIEWER (must be the assigned one) | none | `SchedulingResponse` |
| `POST /{id}/candidate-cancel` | CANDIDATE (must be the round's own candidate) | none | `InterviewRoundResponse` — no pipeline progression; rejected if already `IN_PROGRESS` |

`InterviewRoundResponse`: `{id, processId, roundNumber, roundType, status, result, durationMinutes, bufferMinutes, scheduledStart, scheduledEnd, timezone, dependsOnRoundId, rescheduleCount}`

`status`: `PENDING, SCHEDULING, SCHEDULED, IN_PROGRESS, COMPLETED, RESCHEDULE_REQUIRED, CANCELLED`
`result`: `PENDING, PASS, FAIL, HOLD`
`roundType`: `SCREENING, TECHNICAL, MANAGERIAL, HR`

**Idempotency**: `book` and `switch-interviewer` require `idempotencyKey` — generate one UUID
per *user action* (button click), and reuse the exact same key if retrying that same action
(network failure, double-click). A retried request with the same key returns the original
response unchanged, never double-books. `reschedule`/`cancel`/`complete`/`result` have no
idempotency key of their own; retrying those safely no-ops or 409s instead (the round's own
state machine catches it), it just won't return byte-identical cached output.

**Every `book`/`switch-interviewer` call re-validates from scratch** (fresh availability, fresh
conflict check, DB row locks) even if the slot came from a `recommend` call seconds earlier —
never trust a previously-returned slot as still valid; if it's gone stale, expect a `422`
(`SchedulingException`) and re-run `recommend`.

```bash
curl -X POST http://localhost:8080/api/interviews/$ROUND_ID/book \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "interviewerId":"...", "start":"2026-09-15T14:00:00+05:30", "end":"2026-09-15T15:00:00+05:30",
    "timezone":"Asia/Kolkata", "idempotencyKey":"'"$(uuidgen)"'"
  }'
```

## Google Calendar Integration

Per-user OAuth (Phase 20 revision — every user connects their **own** calendar, not one shared
org account). No RBAC restriction beyond "authenticated" — everyone manages only their own
connection.

| Endpoint | Role | Notes |
|---|---|---|
| `GET /api/integrations/google-calendar/authorize` | any authenticated user | -> `{"authorizationUrl": "https://accounts.google.com/..."}` — navigate the browser there |
| `GET /api/integrations/google-calendar/callback` | **public** | Google redirects here after consent. Redirects on to `{APP_BASE_URL}/settings/integrations?google=connected\|error&message=...` if `APP_BASE_URL` is set, else shows a plain HTML page |
| `GET /api/integrations/google-calendar/status` | any authenticated user | -> `GoogleCalendarStatusResponse{activeProvider, connected, connectedEmail, expiresAt}` for the caller's own connection |
| `POST /api/integrations/google-calendar/webhook` | **public** (Google calls this) | Push-notification receiver, not for manual use |

**Setup required for real use** (all via env vars — see `.env.example`): `GOOGLE_CLIENT_ID`,
`GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI` (must point at *this backend's* `/callback` URL,
registered as an authorized redirect URI in Google Cloud Console — never the frontend's own
URL), and `CALENDAR_PROVIDER=google` (defaults to `noop`, which makes every calendar operation a
harmless no-op). Until then, `/authorize` and `/callback` respond with a clear `502`
"Google OAuth is not configured" rather than a stack trace.

When connected, an interview event (with a generated Google Meet link, via `conferenceData`) is
created on the **assigned interviewer's** calendar (not the candidate's or recruiter's), with
every other participant added as an attendee by email address. Every create/update/cancel call
uses `sendUpdates("all")`, so Google sends its own real invite/update/cancellation email to
every attendee and adds the event (with the Meet link) to their own calendar if they open and
accept it — this works for a candidate who has never connected Google Calendar to this system at
all, since Google's invite only needs a valid email address, not our OAuth connection. This is
separate from and in addition to the app's own `IN_APP` notifications (see Notifications below);
it is not routed through `NotificationService`/SMTP at all — Google delivers it directly.

## Notifications

| Endpoint | Role | Notes |
|---|---|---|
| `GET /api/notifications` | any authenticated user | The caller's own notifications, newest first |

`NotificationResponse`: `{id, userId, interviewRoundId, type, channel, status, sentAt, createdAt}`

`type` values in use: `INTERVIEW_SCHEDULED, INTERVIEW_RESCHEDULED, INTERVIEW_CANCELLED,
INTERVIEWER_CANCELLED, INTERVIEWER_REPLACED, INTERVIEW_INVITATION_DECLINED,
INTERVIEW_REMINDER_24H, INTERVIEW_REMINDER_1H`. `channel`: `IN_APP` (delivered immediately, the
row itself is the delivery) or `EMAIL` (sent via SMTP if `NOTIFICATION_EMAIL_PROVIDER=smtp` is
configured — see `.env.example`; nothing currently *chooses* EMAIL over IN_APP for these system
notifications, see [Known gaps](#known-gaps)). `status`: `PENDING, SENT, FAILED`.

## Audit Logs

| Endpoint | Role | Query params (all optional, combinable) |
|---|---|---|
| `GET /api/admin/audit-logs` | ADMIN | `actorId`, `action`, `entityType`, `entityId`, `dateFrom`, `dateTo` (ISO-8601 offset date-times), `limit` (default 200) |

`AuditLogResponse`: `{id, actorId, actorType, action, entityType, entityId, metadata, createdAt}`
(`actorType`: `USER` or `SYSTEM` — many audit rows are system-triggered, e.g. calendar webhook
reconciliation, with no human actor).

## Agentic AI

Both RECRUITER/ADMIN only — same as the deterministic scheduling endpoints this orchestrates.

### `POST /api/ai/schedule`

Natural-language front end. Body: `AiScheduleRequest{message}`. The model autonomously calls
read-only tools (candidate/job/round lookups, interviewer matching, slot finding, conflict
checks) and returns:

```json
{
  "interpretedRequest": "Schedule Rahul's Java technical interview next week, prefer afternoon",
  "candidate": {"id": "...", "name": "Rahul", "status": "TECHNICAL"},
  "round": {"id": "...", "roundType": "TECHNICAL", "status": "PENDING"},
  "eligibleInterviewers": [ {"interviewerId": "...", "name": "...", "eligible": true, "score": 12.0} ],
  "recommendedSlots": [ {"interviewerId": "...", "start": "...", "end": "...", "score": 10.7, "reason": "..."} ],
  "alternatives": [],
  "reason": "Found 3 slots matching the request; top pick is Monday 2pm with Arjun (skill match 12.0, preferred time).",
  "confirmationRequired": true,
  "action": {"type": "BOOK_INTERVIEW", "roundId": "...", "interviewerId": "...", "start": "...", "end": "...", "timezone": "Asia/Kolkata", "reason": null}
}
```

Nothing here is persisted — a non-null `action` is only a **proposal**. For a purely
informational question ("what's Rahul's status"), `action` is `null` and
`confirmationRequired` is `false`.

`action.type` is one of: `BOOK_INTERVIEW, RESCHEDULE_INTERVIEW, CANCEL_INTERVIEW,
SWITCH_INTERVIEWER, ADVANCE_CANDIDATE_TO_NEXT_ROUND, MARK_CANDIDATE_REJECTED`.

### `POST /api/ai/confirm`

Executes a previously-returned `action` for real, via the exact same backend services the plain
REST endpoints above use (full locking, fresh conflict checks, RBAC) — a stale/hallucinated
proposal is rejected exactly like a stale direct REST call would be.

```json
{
  "action": {"type": "BOOK_INTERVIEW", "roundId": "...", "interviewerId": "...", "start": "...", "end": "...", "timezone": "Asia/Kolkata"},
  "idempotencyKey": "a-client-generated-uuid"
}
```

Returns `AiConfirmResponse{success, actionType, result, message}` where `result` is whatever the
underlying operation returns (`BookingResponse`, `SchedulingResponse`, or
`InterviewRoundResponse`, depending on `actionType`) — same shapes documented under
[Interviews](#interviews-booking-cancellation-rescheduling-replacement) above.

`idempotencyKey` is only meaningfully deduplicated for `BOOK_INTERVIEW`/`SWITCH_INTERVIEWER` (the
underlying services that actually support it); the other action types are still safe to retry,
just not byte-identical on retry (see the Interviews section's idempotency note).

**Exception — `AUTO_SWITCH_IF_QUALIFIED`**: if `scheduling_config.interviewer_replacement_policy`
is set to `AUTO_SWITCH_IF_QUALIFIED`, a `SWITCH_INTERVIEWER` proposal from `/schedule` is executed
immediately server-side — the response already reflects the switch, `confirmationRequired` is
`false`, and calling `/confirm` for it isn't needed (there's nothing left to confirm).

**AI provider**: OpenAI-compatible client pointed at OpenRouter (`AI_API_KEY`/`AI_MODEL` env
vars — see `.env.example`). Blank by default; `/schedule` returns a `500` until a real key is
set (same "fails at use, not at boot" pattern as Google Calendar/email).

---

## Known gaps

Documented here rather than silently left for someone to discover:

- **No unread/read tracking for notifications.** `GET /api/notifications` returns everything,
  newest first; there's no schema column to mark one read, so a frontend notification badge
  needs its own client-side "seen" heuristic (e.g. `localStorage` timestamp of last visit) until
  a backend migration adds one.
- **No scheduling-config management or analytics endpoints.** Phase 25 (admin dashboards,
  scheduling-config editing, interviewer utilization/success-rate metrics) was skipped by
  request. `scheduling_config` (working hours, buffer, notice period, max reschedules,
  replacement policy) exists and is read by the engine, but nothing exposes it for editing via
  API today — it would need a direct database update.
- **`NotificationService`'s own EMAIL channel is implemented but nothing chooses it.**
  `NotificationService` supports `EMAIL` delivery (`SmtpEmailSender`, real SMTP via
  `NOTIFICATION_EMAIL_PROVIDER=smtp`), but every existing call site (booking, cancellation,
  rescheduling, reminders, etc.) uses `IN_APP`. Wiring specific notification types to also send
  email through *this system's own* SMTP is a deliberate follow-up decision, not done here. This
  is separate from Google's own calendar invite email (see Google Calendar Integration above),
  which already goes out for real for every booking/reschedule/cancellation once
  `CALENDAR_PROVIDER=google` is configured — that path doesn't touch `NotificationService`/SMTP
  at all, so it works independently of this gap.
- **`updateEvent` on the Google Calendar provider is implemented but unreachable.** A reschedule
  or interviewer switch always cancels the old calendar event and creates a fresh one (the new
  slot can have a different interviewer/participants entirely) — see `README.md`'s Phase 20
  section for the full reasoning.
- **Google Calendar push notifications are never actually sent by Google.** `POST
  /api/integrations/google-calendar/webhook` and `CalendarReconciliationService`'s drift
  detection (deleted event, time changed externally, an attendee declined) are fully
  implemented, but the `events.watch()` call that registers a push channel with Google in the
  first place was never built (see `CalendarWebhookService`'s own Javadoc) — so Google has no
  channel to notify on and the webhook path sits dormant. `CalendarReconciliationService` also
  runs on a `@Scheduled` 10-second poll (skipped unless `CALENDAR_PROVIDER=google`) specifically
  so drift — including an interviewer or other attendee declining directly in their own Google
  Calendar rather than through this app — is still caught, just within one poll interval
  instead of instantly. A 10-second interval is aggressive (chosen for fast feedback while
  testing) and calls Google's API once per tracked future event on every tick; a real
  deployment with many upcoming interviews would want this much longer, or the `events.watch()`
  channel this was meant to stand in for.
