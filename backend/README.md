# Interview Scheduler Backend

Backend for the **Agentic AI Smart Interview Scheduling Platform** — a modular-monolith
Spring Boot service that is the single source of truth for candidates, jobs, interview
pipelines, availability, and deterministic interview scheduling. A future Agentic AI layer
will orchestrate this backend through controlled service methods; it will never access the
database directly.

Status: **Phase 8 complete** (interviewer + skill matching). See [Implementation Phases](#implementation-phases).

> Phase numbering follows the project's master prompt (Phase 0–40), which supersedes an
> earlier, coarser 17-phase draft. Phases 1 and 2 below were built under the old numbering
> and re-validated against the updated spec (three enum values were added to the schema:
> `HIRING_MANAGER` on `interview_participants.participant_role`, `PENDING` on
> `calendar_events.status`, `SMS` on `notifications.channel`).

## Architecture

```
React Frontend (not in this repo yet)
       |
       v
Spring Boot REST API
       |
       +----------------------+
       |                      |
       v                      v
Business Services          Agent Tools (future)
       |                      |
       +----------+-----------+
                  |
                  v
             PostgreSQL
             (Supabase)
```

External integrations (Google Calendar, Google Meet, email, LLM provider) are added later as
modular components under `integration/`.

## Technology Stack

- Java 21, Spring Boot 3.5
- Maven (via Maven Wrapper — no local Maven install required)
- Spring Web, Spring Data JPA, Spring Security, Bean Validation, Spring Boot Actuator
- PostgreSQL (Supabase) via JDBC + Flyway migrations
- JWT (jjwt) + BCrypt for authentication
- Lombok
- springdoc-openapi (Swagger UI)
- JUnit 5, Spring Boot Test, Testcontainers (PostgreSQL) for integration tests

## Package Structure

```
com.interviewscheduler
├── config          application-wide configuration beans
├── security         JWT filter chain, Spring Security config
├── auth              register/login, JWT issuance
├── user              accounts, roles, status
├── candidate        profiles, skills, pipeline status
├── job                postings, required skills
├── interview        processes, rounds, booking, cancellation, rescheduling
├── interviewer     profiles, skills, matching/eligibility
├── availability    candidate + interviewer availability windows
├── scheduling      slot finding, conflict detection, ranking engine
├── notification    notification records + channel delivery
├── audit              audit log records
├── admin             governance: users, scheduling config, analytics
├── integration       calendar/meeting/email/LLM (added later, kept modular)
└── common            shared exceptions, error format, utilities
```

Business logic lives in `service` classes, never in `controller` classes. JPA entities are
never returned directly from REST endpoints — DTOs + mappers sit in between.

## Prerequisites

- JDK 21 (a JDK 21 install is required; other JDKs on the machine are fine alongside it)
- No local Maven install needed — use the bundled wrapper (`./mvnw` / `mvnw.cmd`)
- A Supabase PostgreSQL project (see [Supabase Setup](#supabase-setup)) — required starting Phase 2

## Environment Variables

Copy `.env.example` to `.env` for local reference (Spring Boot does not read `.env` files
automatically — export the variables into your shell, or configure them in your IDE's run
configuration / the deployment platform's environment settings).

| Variable | Required from | Purpose |
|---|---|---|
| `DATABASE_URL` | Phase 2 | JDBC URL of the Supabase/PostgreSQL database |
| `DB_USERNAME` | Phase 2 | Database username |
| `DB_PASSWORD` | Phase 2 | Database password |
| `JWT_SECRET` | Phase 5 | Secret used to sign JWTs |
| `SERVER_PORT` | optional | Defaults to `8080` |

Never commit real secrets. `.env` is gitignored.

## Supabase Setup

1. Create a project at [supabase.com](https://supabase.com).
2. In the project's **Settings → Database**, copy the connection info (host, port, database,
   user, password).
3. Use the **direct connection** (port `5432`) or the **Session pooler**, not the
   **Transaction pooler** (port `6543`). The transaction pooler doesn't support JDBC
   prepared statements the way Hibernate uses them; the direct connection or session pooler
   do.
4. Build the JDBC URL: `jdbc:postgresql://<host>:<port>/<database>`.
5. Set `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD` in your environment (see
   `.env.example`).

The application talks to Supabase purely as PostgreSQL over JDBC — no Supabase-specific SDK
is used, so the same code works against local PostgreSQL, Supabase, Neon, or Render Postgres.

## Database Schema & Flyway

The full schema (17 tables, matching the spec's ER model) lives in
`src/main/resources/db/migration/V1__create_users.sql` through `V17__create_scheduling_config.sql`,
applied automatically on startup. Highlights:

- All primary keys are `UUID DEFAULT gen_random_uuid()`.
- Status/type fields are `VARCHAR` with a `CHECK` constraint enumerating the allowed values
  (portable across PostgreSQL providers, easy to read in a table browser).
- `candidates.current_round_id` is added without its FK in `V3` (the `interview_rounds` table
  doesn't exist yet) and the FK constraint is attached at the end of `V8`, once it does — this
  is the one circular reference in the schema (candidate ↔ its current round).
- `scheduling_config` seeds itself with one default row in `V17` so the scheduling engine
  always has a config to read.
- `spring.jpa.hibernate.ddl-auto=validate` — Flyway is the only thing allowed to change the
  schema; Hibernate (from Phase 3 onward) only checks that entities match it.

All 17 migrations were verified end-to-end against a real embedded PostgreSQL instance
(apply cleanly in order, FKs resolve, re-running is a no-op) and the packaged Spring Boot jar
was booted against that same instance to confirm `DataSourceAutoConfiguration` → Flyway →
Hibernate all wire together correctly.

## Local Setup

```bash
# From the backend/ directory
./mvnw clean package        # macOS/Linux
mvnw.cmd clean package      # Windows

./mvnw spring-boot:run      # or: java -jar target/interview-scheduler-backend.jar
```

The app starts on `http://localhost:8080`.

## How to Run

```bash
./mvnw spring-boot:run
```

Health check: `GET http://localhost:8080/actuator/health` → `{"status":"UP"}`

## How to Test

```bash
./mvnw test
```

## Swagger

Once Swagger is wired (Phase 17): `http://localhost:8080/swagger-ui.html`
(the springdoc dependency is already on the classpath and auto-exposes this URL).

## Implementation Phases

| Phase | Scope | Status |
|---|---|---|
| 0 | Requirements + architecture baseline | Informally covered (this README + prior design discussion); no standalone doc yet |
| 1 | Spring Boot foundation | ✅ Done |
| 2 | Supabase PostgreSQL + Flyway | ✅ Done |
| 3 | JPA entities + enums + repositories | ✅ Done |
| 4 | DTOs + validation + errors | ✅ Done |
| 5 | Authentication + JWT + RBAC | ✅ Done |
| 6 | Users + candidates + jobs | ✅ Done |
| 7 | Interview process + state machine | ✅ Done |
| 8 | Interviewer + skill matching | ✅ Done |
| 9 | Availability + working hours + timezones | ⏳ Next |
| 10 | Conflict detection | Pending |
| 11 | Slot finding + ranking | Pending |
| 12 | Booking + concurrency + idempotency | Pending |
| 13 | Normal recruiter scheduling (wiring) | Pending |
| 14 | Reusable rescheduling engine | Pending |
| 15 | Interviewer cancellation | Pending |
| 16 | Backup + replacement | Pending |
| 17 | Participant decline + rescheduling | Pending |
| 18 | Pipeline consistency | Pending |
| 19 | Calendar provider abstraction | Pending |
| 20 | Google OAuth + Calendar + Meet | Pending |
| 21 | Google Calendar webhook + reconciliation | Pending |
| 22 | Notifications | Pending |
| 23 | Reminders | Pending |
| 24 | Audit logging (service + API) | Pending |
| 25 | Admin + analytics | Pending |
| 26 | Agentic AI orchestration | Pending |
| 27 | AI confirmation + safety | Pending |
| 28 | Complete backend testing | Pending |
| 29 | API documentation | Pending |
| 30 | Deploy backend | Pending |
| 31–40 | Frontend (not started per spec — backend only for now) | Pending |

## Entities & Repositories (Phase 3)

All 17 tables have a matching `@Entity` (one-to-one with the migrations) plus a Spring
Data `JpaRepository`, organized into the same packages as the schema's domain:

- `common` — `Skill`
- `user` — `User`, `Role`, `UserStatus`
- `candidate` — `Candidate`, `CandidateStatus`, `CandidateSkill`
- `job` — `Job`, `JobStatus`, `JobSkill`
- `interview` — `InterviewProcess`, `ProcessStatus`, `InterviewRound`, `RoundType`,
  `RoundStatus`, `RoundResult`, `RoundRequirement`, `InterviewParticipant`,
  `ParticipantRole`, `AssignmentType`, `ParticipantStatus`
- `interviewer` — `InterviewerProfile`, `InterviewerSkill`
- `availability` — `Availability`, `AvailabilityStatus`
- `integration` — `CalendarEvent`, `CalendarEventStatus`
- `notification` — `Notification`, `NotificationChannel`, `NotificationStatus`,
  `NotificationType` (not DB-constrained, so new types don't need a migration)
- `audit` — `AuditLog`, `ActorType`, `AuditAction` (same not-DB-constrained pattern)
- `admin` — `SchedulingConfig`, `ReplacementPolicy`

Notable choices:
- IDs use `@GeneratedValue(strategy = GenerationType.UUID)` (Hibernate generates the UUID
  application-side) rather than relying on the DB's `gen_random_uuid()` default, so entity
  behavior doesn't depend on which DB the app is pointed at.
- `createdAt`/`updatedAt` use Hibernate's `@CreationTimestamp`/`@UpdateTimestamp` rather
  than the DB defaults, for the same reason.
- All status/type fields are Java enums mapped `@Enumerated(EnumType.STRING)` against the
  matching `VARCHAR` + `CHECK` column.
- Every `@ManyToOne` is `FetchType.LAZY`, and `@ToString` excludes relationship fields —
  entities are never returned directly from the API (DTOs arrive in Phase 4) but this also
  avoids Lombok-generated `toString()`/`equals()` walking into lazy proxies or circular
  relationships (e.g. `Candidate.currentRound` → `InterviewRound.process` → `Candidate`).
  `equals`/`hashCode` are id-only (`@EqualsAndHashCode(of = "id")`).
- `AuditLog.metadata` maps the `jsonb` column via Hibernate's native
  `@JdbcTypeCode(SqlTypes.JSON)` to a `Map<String, Object>` — no extra library needed.

**How this was verified:** built the jar and booted it with `spring.jpa.hibernate.ddl-auto=validate`
against a real (embedded) PostgreSQL instance already carrying the Phase 2 schema. Hibernate's
schema validator compares every entity's columns, types, and nullability against the actual
table — the app reached `{"status":"UP"}` with zero validation errors, and Spring Data logged
`Found 17 JPA repository interfaces`, confirming both the entity↔schema mapping and repository
wiring are correct, not just that the code compiles.

The context-load test (`InterviewSchedulerApplicationTests`) now uses Testcontainers
(`@ServiceConnection` + `PostgreSQLContainer`) instead of assuming a DB is already configured,
since Phase 2 made a real datasource mandatory to boot. **Running `./mvnw test` requires Docker**
locally; this wasn't available in the environment these phases were built in, so verification
here used a manually-started embedded Postgres instead (equivalent to what Testcontainers does,
just outside a JUnit `@Container` lifecycle) — the Testcontainers test itself compiles cleanly
but hasn't been executed end-to-end. Run it once with Docker available to confirm.

## DTOs, Validation & Errors (Phase 4)

Request/response shapes are Java **records** (Java 21, immutable, minimal boilerplate) rather
than Lombok classes — Bean Validation annotations (`@NotNull`, `@NotBlank`, `@Email`, `@Min`/
`@Max`, `@DecimalMin`) go directly on record components and work with `@Valid` the same way
they would on a regular class. Response records carry a static `from(Entity)` factory for
mapping instead of a separate mapper layer — the mapping is a few field reads, so a dedicated
mapper class/library would be pure ceremony at this size.

DTOs live directly in their domain package (`auth`, `user`, `candidate`, `job`, `interview`,
`interviewer`, `availability`, `scheduling`, `notification`, `audit`) — no separate `dto`
subpackage, consistent with how entities/enums/repositories were placed in Phase 3. New in
this phase:
- `scheduling` package now exists as more than a placeholder: `ConflictType` and
  `SchedulingReasonCode` enums plus the six DTOs the deterministic engine (Phases 9–14) will
  return — `SchedulingRequest/Response`, `SlotResponse`, `ConflictResponse`,
  `ReplacementResponse`, `BookingRequest/Response`.
- Two DTOs not explicitly named in the spec but required by endpoints it does list:
  `UpdateUserStatusRequest` (backs `PATCH /api/users/{id}/status`) and `JobSkillResponse`
  (backs `GET /api/jobs/{id}/skills`, mirroring `CandidateSkillResponse`).

**Errors** — `common/exception` has all ten exceptions from the spec plus a
`@RestControllerAdvice` (`GlobalExceptionHandler`) that maps each to an HTTP status and the
documented `ErrorResponse` shape (`timestamp`, `status`, `error`, `message`, `path`):

| Exception | Status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `DuplicateResourceException`, `InvalidStateTransitionException`, `ConflictException` | 409 |
| `InvalidBookingException`, `SchedulingException` | 422 |
| `CalendarIntegrationException` | 502 |
| `NotificationException` | 500 |

Bean Validation failures (`MethodArgumentNotValidException`) return 400 with per-field
messages joined into one string; malformed JSON, Spring Security's `AccessDeniedException`/
`AuthenticationException`, and any uncaught exception all get a matching consistent response
too, so no endpoint can leak a raw stack trace.

`SchedulingException` carries an optional `reasonCode` (one of `SchedulingReasonCode`) appended
to its error message — but note that "no slots found" from a search endpoint is a normal `200`
with `SchedulingResponse.reasonCode` set, not an exception; `SchedulingException` is reserved
for scheduling failures during an operation that's supposed to conclusively succeed (e.g. a
fresh conflict check failing mid-booking).

**Verified:** the project compiles clean, and the packaged jar was re-booted against a fresh
embedded PostgreSQL (same method as Phase 3) — `GlobalExceptionHandler`'s `@RestControllerAdvice`
bean loads without conflicting with Spring Security's own exception handling, health still
returns `{"status":"UP"}`. Since no controllers exist yet (Phase 5+), the handler mappings
themselves aren't exercised by a real HTTP error yet — that's implicit verification once
Phase 5's `AuthController` lands.

## Authentication, JWT & RBAC (Phase 5)

`POST /api/auth/register` and `POST /api/auth/login` are the only public API endpoints —
everything else requires a valid JWT (`Authorization: Bearer <token>`), enforced in
`security/SecurityConfig`. Passwords are BCrypt-hashed (`PasswordEncoder` bean), JWTs carry
`userId` (subject), `email`, and `role` claims, signed HS-something (jjwt picks the strongest
HMAC variant for the key size) via `JwtService`.

- **Login** goes through Spring Security's normal `AuthenticationManager` →
  `DaoAuthenticationProvider` → `CustomUserDetailsService` (one DB lookup, verifies the BCrypt
  hash, and — because `UserPrincipal.isEnabled()` reflects `UserStatus.ACTIVE` — an
  INACTIVE/SUSPENDED account fails login the same way a wrong password does, without revealing
  which case it was).
- **Every other request** is authenticated by `JwtAuthenticationFilter`, which validates the
  JWT's signature/expiry and builds the `Authentication` directly from its claims — no DB hit
  per request. An invalid/missing token just leaves the security context empty; the "must be
  authenticated" rule then rejects it uniformly.
- **RBAC** — `@EnableMethodSecurity` is on, so `@PreAuthorize("hasRole('RECRUITER')")` etc. is
  ready to use on Phase 6+ controllers. `security/SecurityUtils.currentUser()` gives services
  access to the caller's id/role for the *object-level* checks the spec calls out (e.g. "a
  candidate can only read their own profile") — those checks live in Phase 6+ services once
  the resources they guard actually exist; Phase 5 only had `/auth/*` endpoints to secure.
- **Error consistency** — a rejection at the security-filter level (no token, bad token,
  insufficient role) never reaches `GlobalExceptionHandler` normally, since it happens before
  Spring MVC dispatch. `RestAuthenticationEntryPoint`/`RestAccessDeniedHandler` write the same
  `ErrorResponse` JSON shape by hand so the API never exposes two different error formats
  depending on where a rejection happened.

**Verified with real HTTP requests** (not just a context-load boot) against the packaged jar
and an embedded PostgreSQL: register → 201 with no password hash in the response; duplicate
email → 409; invalid payload (blank name, bad email, short password) → 400 with per-field
messages; login with correct/wrong password → 200 with a working bearer token / 401; an
unmatched route with no token → 401; the same route with a valid token → 404 (confirms the
JWT filter genuinely authenticates before the 404 is reached); Swagger's `/v3/api-docs` stays
public. Two real bugs turned up and were fixed during this testing, not left for later:
1. `AuthService.register` used `save()` and built the response in the same transaction —
   `@CreationTimestamp`/`@UpdateTimestamp` populate at flush time, so `createdAt`/`updatedAt`
   came back `null`. Fixed with `saveAndFlush()`.
2. The catch-all `Exception` handler was swallowing Spring's `NoResourceFoundException`
   (thrown for any unmatched route since Spring Framework 6.1) and turning a plain 404 into a
   500. Added a specific handler for it.

## Users, Candidates & Jobs (Phase 6)

`UserService`/`CandidateService`/`JobService` + their controllers implement the full API list
for these three domains, plus a minimal `AuditService.log(...)` (write-only — the query API
is Phase 24) that every mutation now calls.

**RBAC + object-level authorization**, layered two ways:
- Role gates use `@PreAuthorize` on the controller (e.g. `POST /api/jobs` requires
  `RECRUITER` or `ADMIN`).
- Object-level ownership (a role check alone can't express "only *this* candidate's own
  record") is enforced in the service, via `SecurityUtils.currentUser()` compared against
  the resource's owner: `GET/PUT /api/candidates/{id}` and `/skills` allow RECRUITER/ADMIN
  for any candidate, or the candidate themselves — never another candidate.
  `GET/PUT /api/users/{id}` follows the same pattern (self or ADMIN);
  `PATCH /api/users/{id}/status` is ADMIN-only (governance action, not self-service).
- `DELETE /api/jobs/{id}` refuses (409) if the job has any interview processes — deleting it
  would cascade through interview data, which the spec explicitly says never to do silently
  (jobs get closed via `status`, not deleted, once anything references them).

Skills themselves (`common.Skill`) have no CRUD endpoint — the spec's API list doesn't
include one; they're meant to be seeded (Phase 41 dev seed data), and `candidate_skills`/
`job_skills` just reference existing skill ids, returning 404 if the id doesn't exist.

**Verified with real HTTP requests**, not just a boot check: registered a recruiter and two
candidates, had the recruiter create one candidate's profile, and confirmed — the *other*
candidate gets 403 reading it, the owning candidate gets 200, a candidate gets 403 listing
all candidates while the recruiter gets 200, adding a duplicate skill gets 409, a candidate
gets 403 creating a job while a recruiter gets 201, a non-admin gets 403 changing another
user's status while the user themself can update their own name/timezone (and a different
user cannot), and deleting a job with no processes succeeds. Queried `audit_logs` directly
afterward and confirmed `USER_CREATED` (×4, from registration), `CANDIDATE_CREATED`, and
`JOB_CREATED` rows were actually written — not just that the endpoints returned 2xx.

This testing round caught three real bugs, fixed before moving on:
1. **`LazyInitializationException`** on nearly every read endpoint — `findById`/`findAll`/
   `getSkills`/etc. weren't `@Transactional`, so by the time the response DTO's `from()`
   touched a lazy relation (`candidate.getUser().getName()`, `jobSkill.getSkill().getName()`),
   Hibernate's session had already closed. Fixed by adding `@Transactional(readOnly = true)`
   to every read method across `UserService`/`CandidateService`/`JobService` — this is a
   pattern to keep applying to every service from here on, not a one-off patch.
2. The generic `Exception` handler in `GlobalExceptionHandler` returned a 500 without logging
   anything — the bug above was invisible in the response *and* the logs. Added `log.error(...)`
   with the full stack trace before building the response.
3. (Carried over from Phase 5, still worth restating): `saveAndFlush` over `save` wherever a
   response is built in the same transaction as the write — `Job`/`Candidate`/`User` creation
   all needed this for `createdAt`/`updatedAt` to come back non-null, same root cause as the
   `AuthService.register` bug.

## Interview Process & Round State Machine (Phase 7)

`InterviewProcessService` implements the three endpoints the spec lists plus the round
completion/result actions its Phase 4 DTOs (`CompleteRoundRequest`, `RoundResultRequest`)
were already shaped for:

- `POST /api/interview-processes` (`InterviewProcessController`) — RECRUITER/ADMIN only.
- `GET /api/interview-processes/{id}` — self-or-staff, same object-level pattern as Phase 6.
- `GET /api/candidates/{id}/interview-process` (on `CandidateController`, since the path is
  candidate-scoped) — the candidate's current ACTIVE process.
- `POST /api/interviews/{id}/complete` / `POST /api/interviews/{id}/result`
  (`InterviewController`) — "an interview" in these URLs is one `interview_rounds` row, the
  thing that actually gets completed/resolved; Phases 12+ (booking, rescheduling,
  cancellation) add more actions under this same `/api/interviews/{id}` base per the spec.

**Creating a process** creates all four rounds (SCREENING, TECHNICAL, MANAGERIAL, HR;
round *n* depends on round *n-1* via `depends_on_round_id`) and moves the candidate
APPLIED -> SCREENING in the same transaction — this is the one place `CandidateStatus` and
`RoundType` names are intentionally identical, so `toCandidateStatus(RoundType)` is a direct
mapping. `InterviewProcessRepository.findByCandidateIdAndStatus` returns an `Optional`, not a
`List` — that was Phase 3's way of encoding "one ACTIVE process per candidate at a time";
Phase 7 enforces it explicitly (`DuplicateResourceException` on a second attempt) rather than
letting a schema-level assumption go unchecked. Starting a process for a REJECTED/WITHDRAWN
candidate is also refused.

**Round status vs. round result** are deliberately separate actions:
`/complete` moves `SCHEDULED`/`IN_PROGRESS` -> `COMPLETED` (the interview happened, no
verdict yet) and refuses to complete anything other than the candidate's *current* round
(prevents completing a future round out of order). `/result` then requires the round to
already be `COMPLETED`, refuses a second result on the same round (`result` must still be
`PENDING`) and refuses any result on a `CANCELLED` round — the three prevention rules the
spec calls out by name. The result drives progression:

- **PASS** — advance to `roundNumber + 1` if it exists (process `currentRound` and the
  candidate's `currentStatus`/`currentRound` all move together); on the last round (HR) the
  candidate becomes `SELECTED` and the process `COMPLETED` instead.
- **FAIL** — candidate `REJECTED`, process `REJECTED`, and every later round that isn't
  already `COMPLETED`/`CANCELLED` is set `CANCELLED` in the same transaction ("future rounds
  invalidated").
- **HOLD** — the round records `HOLD` and nothing else changes; no automatic retry/requeue
  mechanism exists yet since the spec doesn't define one at this phase.

Rounds don't yet have a way to reach `SCHEDULED` (that's Phase 12's booking flow) — until
then `/complete` and `/result` are only reachable by moving a round's status directly in the
database, which is expected at this point in the build.

**Verification:** this phase was built and reviewed in an environment with no network route
to the configured Supabase instance and no local Postgres/Docker available, so — unlike
Phases 1-6 — it was **not** boot-tested against a real database or exercised with real HTTP
requests. What was verified: `./mvnw clean compile`, `test-compile`, and `package` all succeed
cleanly (JDK 21) with the new `InterviewProcessService`, `InterviewProcessController`,
`InterviewController`, and the `CandidateController` addition, and the state-machine logic
above was traced by hand against every prevention rule in the spec. **Run `./mvnw spring-boot:run`
against a real database and exercise all five endpoints before starting Phase 8**, the same
way every prior phase was closed out.

## Interviewer + Skill Matching (Phase 8)

`InterviewerService` covers the five listed endpoints (`GET /api/interviewers`,
`GET/POST /api/interviewers/{id}/skills`, `GET /api/interviewers/{id}`) with the same
self-or-staff object-level pattern as Candidate/Job. There's deliberately **no**
`POST /api/interviewers` — Phase 4's DTO list only ever specified `InterviewerResponse` +
`InterviewerSkillRequest`, never a create/update request, so interviewer profiles are
provisioned outside the API (seed data / direct admin action), not through it. That means
this phase's matching logic can't yet be exercised end-to-end through the API alone — it
needs at least one `interviewer_profiles` row to exist already.

`InterviewerMatchingService.match(roundId)` (`POST /api/interviewers/match`) is the actual
Phase 8 deliverable:

1. **Effective requirements** — round-specific `round_requirements` win if any exist for the
   round; otherwise the algorithm falls back to the job's `job_skills`. (There's no endpoint
   yet to populate `round_requirements` — Phase 8 doesn't add one either — so today this
   almost always falls back to job skills; the round-level override is wired up and ready
   for whenever one is added.)
2. **Hard eligibility** (disqualifying) — missing a required skill, proficiency below that
   skill's minimum, or the interviewer's user account not `ACTIVE`.
3. **Soft ranking** (eligible interviewers only) — summed proficiency across all matched
   skills (required and optional), a bonus for headroom above the minimum, a bonus for
   skills marked `is_primary`, a domain-match bonus (job domain vs. interviewer domain), and
   a workload penalty (count of the interviewer's active `INTERVIEWER` participant rows —
   necessarily 0 for everyone until Phase 12+ starts assigning participants, but the
   computation is real, not a stub).

**A spec discrepancy, resolved explicitly:** the "matching order" line
(`... -> HARD SKILL ELIGIBILITY -> DOMAIN -> ACTIVE STATUS -> WORKLOAD`) reads as if domain
and active-status were sequential filter stages, but the spec's own, more specific
"hard eligibility" list only names missing skill / low proficiency / inactive interviewer /
"allowed for round" (the last of which has no corresponding data field — read here as
trivially satisfied by having an `interviewer_profiles` row at all), while its separate
"soft ranking" list explicitly includes domain. The more specific list wins: **domain is
ranking-only here, not disqualifying.** `MatchInterviewersResponse` returns two lists —
`eligibleInterviewers` (ranked, highest score first) and `ineligibleInterviewers` (each with
its `failureReasons`) — directly matching the spec's "return eligible and failed reasons."

**Verification:** same constraint as Phase 7 — no network route to the configured Supabase
instance and no local Postgres/Docker in this environment, so this was verified with a clean
`compile`/`test-compile`/`package` under JDK 21, not a live boot or real HTTP requests.
**Seed at least one interviewer profile with skills and run `/api/interviewers/match`
against a real round before starting Phase 9**, the same way every prior phase was closed
out with real requests.

## Deployment Preparation (later)

Target: Spring Boot on Render, PostgreSQL on Supabase, frontend on Vercel. No deployment
config is added yet — this is noted here so environment-variable naming stays consistent
with that target from the start.
