# Interview Scheduler Backend

Backend for the **Agentic AI Smart Interview Scheduling Platform** — a modular-monolith
Spring Boot service that is the single source of truth for candidates, jobs, interview
pipelines, availability, and deterministic interview scheduling. A future Agentic AI layer
will orchestrate this backend through controlled service methods; it will never access the
database directly.

Status: **Phase 20 complete** (Google OAuth + Calendar + Meet). See [Implementation Phases](#implementation-phases).

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
   **Note:** the direct connection host (`db.<ref>.supabase.co`) only publishes an IPv6
   (AAAA) DNS record unless the project has the paid IPv4 add-on — on an IPv4-only network
   it's simply unreachable (fails as a DNS/connect error, not an auth error). If so, use the
   **Session pooler** connection string instead (Settings → Database → Connection pooling →
   Session mode; host like `aws-0-<region>.pooler.supabase.com`, username becomes
   `postgres.<project-ref>`) — it resolves to a regular IPv4 address.
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
| 9 | Availability + working hours + timezones | ✅ Done |
| 10 | Conflict detection | ✅ Done |
| 11 | Slot finding + ranking | ✅ Done |
| 12 | Booking + concurrency + idempotency | ✅ Done |
| 13 | Normal recruiter scheduling (wiring) | ✅ Done |
| 14 | Reusable rescheduling engine | ✅ Done |
| 15 | Interviewer cancellation | ✅ Done |
| 16 | Backup + replacement | ✅ Done |
| 17 | Participant decline + rescheduling | ✅ Done |
| 18 | Pipeline consistency | ✅ Done |
| 19 | Calendar provider abstraction | ✅ Done |
| 20 | Google OAuth + Calendar + Meet | ✅ Done |
| 21 | Google Calendar webhook + reconciliation | ⏳ Next |
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

## Availability, Working Hours & Timezones (Phase 9)

`AvailabilityService` implements the four generic endpoints
(`POST/GET/{userId}/PUT/{id}/DELETE/{id}` under `/api/availability`). Every user manages only
their **own** availability — `POST` always creates for the authenticated caller (there's no
target-user field on `AvailabilityRequest`), and `PUT`/`DELETE` require the caller to own the
record; RECRUITER/ADMIN can still read anyone's (`GET /{userId}`), since a recruiter arranging
an interview needs to see it. This isn't just Candidate/Job's usual self-or-staff pattern
narrowed — the spec explicitly calls out "candidate cannot change another candidate's
availability" and never mentions a staff override for *writing* someone else's calendar, so
none was added.

**Two role-scoped read services** (`CandidateAvailabilityService`, `InterviewerAvailabilityService`)
sit alongside the generic one, matching the spec's explicit naming even though Phase 9 doesn't
wire either to its own endpoint — they translate a candidate/interviewer *profile* id to the
underlying user id and exist as the seam Phase 11's slot finder calls ("get candidate
availability" / "get interviewer availability") without knowing that mapping itself.

**Validation, in two layers:**
- *Structural* (Bean Validation, 400) — `@ValidAvailabilityWindow` (class-level, on
  `AvailabilityRequest`) rejects `endTime <= startTime`; `@ValidTimezone` rejects any string
  `ZoneId.of(...)` doesn't accept, catching typos/garbage before they reach a service or the DB.
- *Business policy* (service layer, 409 `ConflictException`) — only for `AVAILABLE` entries
  (marking yourself `UNAVAILABLE` is always accepted, since it only narrows the record, never
  claims bookability): the window must fall inside `scheduling_config`'s `working_start`/
  `working_end`, and a weekend window is rejected unless `allow_weekends` is set.
  `WorkingHoursService` reads that single config row (seeded by V17) for both checks.
- *Overlap* (service layer, 409) — a new/updated window is rejected if it overlaps **any**
  existing entry for the same user, regardless of status. Comparison happens on UTC instants,
  not raw date/time fields: `TimezoneService.toUtcRange` resolves each window through
  `ZonedDateTime` (which is DST-aware — the same wall-clock time maps to a different UTC
  instant depending on the date), and the overlap query scans the target date +-1 day so a
  same-user pair recorded in different timezones is still compared correctly across a
  day-boundary shift.

**Verified live** against the real Supabase database (via the Session pooler — see
[Supabase Setup](#supabase-setup)) with real HTTP requests: same-timezone overlap (409),
a genuine cross-timezone overlap where the raw local times don't overlap but the UTC instants
do (409) and a non-overlapping cross-timezone sanity check (201), a weekend attempt (409), an
outside-working-hours attempt (409), an invalid-timezone string (400), an end-before-start
request (400 — see the `GlobalExceptionHandler` fix below), and self-vs-staff-vs-other
authorization on all four endpoints. One real bug turned up and was fixed, not left for later:
`GlobalExceptionHandler`'s validation handler only read `getFieldErrors()`, so a class-level
constraint like `@ValidAvailabilityWindow` (it applies to the whole record, not one field —
Spring reports it as a *global* error) fell through to a generic "Validation failed" message
instead of the specific one. Fixed by also collecting `getGlobalErrors()`.

## Conflict Detection (Phase 10)

`ConflictDetectionService.checkConflicts` (`POST /api/scheduling/check-conflicts`,
RECRUITER/ADMIN) is a single read-only pass over a *proposed* window for a round — the spec
requires "a fresh conflict check immediately before booking," so Phase 12's booking flow is
expected to call this exact method again right before committing, not just once at
slot-finding time. Request: `roundId`, `start`/`end` (both `OffsetDateTime`, so comparisons
are correct regardless of which offset each side used), an optional `interviewerId`
(an interviewer *profile* id, resolved to its user internally), and an optional
`requiredParticipants` list of `{userId, role}` for the recruiter/hiring-manager participants
the spec calls out beyond candidate/interviewer. Response: `{hasConflicts, conflicts[]}`,
reusing the existing `ConflictResponse` DTO — `hasConflicts` is just "is `conflicts` non-empty."

**One query does most of the work.** `InterviewRoundRepository.findScheduledOverlapsForUser`
(built in Phase 3, javadoc already said "the basis for fresh conflict checks before booking")
is called once per participant with the window expanded by the round's `buffer_minutes` on
each side. Each hit is then classified against the *unexpanded* request window:

- Overlaps the real window -> the participant's own conflict type (`CANDIDATE_CONFLICT`,
  `INTERVIEWER_CONFLICT`, `RECRUITER_CONFLICT`, `HIRING_MANAGER_CONFLICT`), plus a
  `CALENDAR_CONFLICT` if that conflicting round already has an active (`PENDING`/`CREATED`/
  `UPDATED`) `calendar_events` row — a stronger signal than a plain internal double-booking,
  since it means an external calendar entry would need to be touched too.
- Falls inside the buffer-expanded window but *not* the real one (including two meetings that
  touch with zero gap) -> `BUFFER_CONFLICT` instead. This one query, split this way, covers
  every scenario the spec lists by name: exact/partial/contained overlap -> the role conflict;
  adjacent meetings and small-gap violations -> `BUFFER_CONFLICT`; no nearby meeting -> nothing.

The other checks are single, independent conditions: `INVALID_TIME_RANGE` (`end <= start` —
short-circuits the rest, since nothing else means anything against a nonsensical range),
`NOTICE_PERIOD_CONFLICT` (`start` inside `scheduling_config.minimum_booking_notice_minutes`
from now), `WORKING_HOURS_CONFLICT` (reuses Phase 9's `WorkingHoursService` — working hours
and weekend policy, read from the request's own `OffsetDateTime.toLocalTime()`/`toLocalDate()`
so no separate timezone field is needed on the request), and `ROUND_DEPENDENCY_CONFLICT`
(the round's `dependsOnRound`, if any, isn't `COMPLETED` with a `PASS` result yet).

**Verified live** against the real Supabase database with real HTTP requests, seeding a
`SCHEDULED` round (plus its `interview_participants` and a `calendar_events` row — nothing
populates these yet since booking is Phase 12) and checking a second round in the same
process against it: exact overlap, partial overlap, and contained overlap all correctly
produced `CANDIDATE_CONFLICT`/`INTERVIEWER_CONFLICT` plus `CALENDAR_CONFLICT`; two meetings
touching with zero gap and a 5-minute gap both correctly produced `BUFFER_CONFLICT` instead;
a 20-minute gap (clear of the 15-minute buffer) produced no conflict. Also verified
`INVALID_TIME_RANGE`, `NOTICE_PERIOD_CONFLICT`, `WORKING_HOURS_CONFLICT`, and
`ROUND_DEPENDENCY_CONFLICT` independently.

**One real bug turned up and was fixed:** Jackson's `OffsetDateTime` deserialization
defaults to normalizing the incoming value to the context (UTC) zone — `"10:00:00+05:30"`
silently became `04:30 Z` (the same instant, but a different `toLocalTime()`), so
`WORKING_HOURS_CONFLICT`'s check against the request's own local time was comparing the
wrong wall-clock value entirely (a 10:00 IST request read as 04:30, and a 20:00 IST request
read as 14:30 — both misjudged). Fixed globally in `application.yml`
(`spring.jackson.deserialization.adjust-dates-to-context-time-zone: false`) rather than
threading a timezone field through every `OffsetDateTime`-carrying DTO — every comparison
elsewhere in the codebase uses instant semantics (`isBefore`/`isAfter`), which are
offset-representation-independent and were never affected.

## Slot Finding + Ranking (Phase 11)

Three endpoints under `/api/scheduling` (RECRUITER/ADMIN), backed by three services with a
clean split of responsibility:

- **`SlotFinderService`** (`POST /find-slots`, spec steps 1-18) — deterministic feasibility
  only, no scoring beyond carrying over each interviewer's Phase 8 match score. Validates the
  candidate/round pair and schedulability (candidate not REJECTED/WITHDRAWN, round `PENDING`
  or `RESCHEDULE_REQUIRED`, its `dependsOnRound` — if any — already `COMPLETED`+`PASS`),
  fetches eligible interviewers from `InterviewerMatchingService`, then intersects everyone's
  `AVAILABLE` windows (candidate, each required participant, then per-interviewer) as UTC
  instant ranges — reusing `TimezoneService` from Phase 9, the same DST/cross-timezone-correct
  machinery that made Availability's own overlap check correct. Within each common window it
  tries candidate start times at a 30-minute stride (capped at 6 per window, to bound the
  search), filters each by notice period / max horizon / weekend-or-excluded-day / preferred
  time / working hours, then runs a **fresh** `ConflictDetectionService.checkConflicts` call
  per surviving candidate — the same method Phase 10 built and the same one Phase 12's booking
  flow is expected to call again immediately before committing.
- **`SlotRankingService`** (`POST /rank-slots`, step 19) — a pure function of
  (request context, slots): takes the interviewer's Phase 8 match score as its base, adds a
  bonus for the requested `preferredInterviewerId` and for falling inside
  `preferredTimeStart`/`preferredTimeEnd`, and applies a small "sooner is better" per-day
  penalty. Deliberately doesn't re-derive or re-check anything — it can rank slots from any
  source, not just this service's own finder.
- **`SchedulingService`** (`POST /recommend`, step 20) — find, then rank, then split into the
  top 3 as `slots` (the recommendation) and the rest as `alternatives`; on zero slots it just
  returns the finder's result (already carrying the `reasonCode`) unchanged.

**No slot found** returns `200` with an empty `slots` list and one of the spec's reason codes —
never an exception, since "nothing bookable" is a legitimate outcome, not a failure. The seven
codes map to distinct, ordered stages of the search (each one only returned if *every* earlier
stage produced at least one candidate): `NO_QUALIFIED_INTERVIEWER` (Phase 8 found nobody
eligible) -> `NO_CANDIDATE_AVAILABILITY` (candidate has no `AVAILABLE` rows in range) ->
`NO_INTERVIEWER_AVAILABILITY` (none of the eligible interviewers do either) ->
`NO_COMMON_SLOT` (everyone has *some* availability, but no window of sufficient duration is
shared by all of them) -> `DATE_RANGE_EXHAUSTED` (a common window existed, but every candidate
start inside it failed notice/horizon/weekend/excluded-day/preferred-time filtering) ->
`OUTSIDE_WORKING_HOURS` (survived those, but not the literal working-hours check — chiefly
reachable via a `preferredTimeStart`/`preferredTimeEnd` that itself falls outside configured
hours, since `AVAILABLE` rows are already constrained to working hours at creation time) ->
`ALL_SLOTS_CONFLICTED` (reached a fresh conflict check, but every candidate had one).

**Two spec gaps, resolved explicitly, not silently:**
- `requiredParticipantIds` is a plain `List<UUID>` (unlike Phase 10's `CheckConflictsRequest`,
  which pairs each id with a `ParticipantRole`) — each id's role is looked up from `users`
  instead of trusted from the caller, which is arguably more correct anyway (role is intrinsic
  to the account, not something the caller should get to assert).
- The system has no `HIRING_MANAGER` **user role** (`User.Role` is only
  `ADMIN`/`RECRUITER`/`INTERVIEWER`/`CANDIDATE`), even though `ParticipantRole` has a
  `HIRING_MANAGER` value for actual interview participants. A required participant is
  classified as `RECRUITER` unless their account role is literally `CANDIDATE` or
  `INTERVIEWER` — this is a pre-existing modeling gap from earlier phases, not something to
  invent a schema change for here.

**Verified live** against the real Supabase database with real HTTP requests (candidate and
interviewer `AVAILABLE` windows overlapping 10:00-12:00 IST on a future Monday): `/find-slots`
returned exactly the 3 valid 60-minute starts the 2-hour common window allows (10:00, 10:30,
11:00 — the 30-minute stride naturally stops there since 11:30+60min would exceed the window);
`/recommend` with `preferredTimeStart=11:00` correctly narrowed to just the 11:00 slot and its
ranking math checked out by hand (base score + preferred-time bonus - sooner-penalty); calling
`/find-slots` against a round already `SCHEDULED` (seeded during Phase 10's testing) correctly
returned 409 before reaching the dependency check; a date range with no candidate availability
correctly returned `NO_CANDIDATE_AVAILABILITY`; `/rank-slots` correctly applied the preferred-
interviewer bonus and re-sorted; a non-RECRUITER/ADMIN caller got 403 on all three endpoints.
No bugs found this round — everything behaved as designed on the first live pass.

## Booking + Concurrency + Idempotency (Phase 12)

`InterviewBookingService.book` (`POST /api/interviews/{id}/book`, RECRUITER/ADMIN) is the
spec's flow almost line for line: idempotency check -> validate round/candidate/interviewer ->
validate participants/dependency -> fresh availability -> fresh conflict check -> concurrency
locks -> upsert participants -> `SCHEDULED` -> calendar record -> notifications -> audit ->
commit (one `@Transactional` method; "commit" is just letting it return normally). Two
existing exceptions finally get the job they were written for back in Phase 4: `InvalidBooking
Exception` for a structurally bad request (`end <= start`), `SchedulingException` for a
deterministic-engine failure at booking time (a fresh conflict check failing) — its
`reasonCode` reuses `ALL_SLOTS_CONFLICTED` from Phase 11's enum rather than inventing a new one,
since it's the same situation, just discovered later.

**PostgreSQL transaction/locking strategy** (the spec asks for this explicitly, so it's
documented at length in the service's own class-level javadoc too): the whole booking is one
transaction at Postgres's default READ COMMITTED isolation — no isolation level change needed,
because correctness here comes from two explicit `SELECT ... FOR UPDATE` row locks, not from
snapshot isolation:
- `InterviewRoundRepository.findByIdForUpdate` — a second transaction targeting the *same
  round* (a double-click, two recruiters racing on one round) blocks here until the first
  commits or rolls back, then reads the round's now-current status and correctly refuses to
  double-book it — no special "already booked" detection needed, the ordinary schedulability
  check does it once the lock releases.
- `InterviewerProfileRepository.findByIdForUpdate` — a second transaction targeting the *same
  interviewer* on a *different* round (which the round lock alone wouldn't catch) also blocks
  here, then its fresh conflict check correctly sees the first transaction's now-committed
  participant/round rows.

Documented honestly, not glossed over: this doesn't lock the candidate or other required
participants the same way. A true concurrent double-booking race on a shared *candidate*
across two different rounds isn't fully serialized by a row lock here — mitigated by Phase 7's
invariant that a candidate has only one ACTIVE process at a time, which narrows the realistic
window for this considerably, but it's not the same hard guarantee the interviewer gets.

**Idempotency** is a separate mechanism layered on top, not a byproduct of the locks:
`common.idempotency.IdempotencyService` + a new `idempotency_keys` table (migration V18,
`UNIQUE (scope, key_value)`) generic enough for any later phase to reuse under its own scope
string. `claim(scope, key)` is called first, inside the same transaction that will perform the
operation: if `(scope, key)` already completed, it deserializes and returns the *original*
response immediately, re-running nothing; if it's found but not yet completed, or if inserting
a new claim row hits the unique constraint, that's a duplicate in flight → `409`. Because the
claim is part of the same transaction as the booking, a request that fails after claiming (bad
input, stale availability, a lost conflict-check race) rolls the claim back too — only a
transaction that reaches `complete()` permanently remembers the key, so a genuinely failed
attempt can still be retried with the same key. The unique constraint is what actually makes
concurrent duplicates safe: a second transaction's insert of the same key blocks on Postgres's
MVCC until the first commits or rolls back, then either fails cleanly (first one succeeded) or
proceeds (first one didn't) — never both succeeding.

Two small shared components came out of this phase's overlap with Phase 11's own validation
needs, extracted rather than copy-pasted a second time: `SchedulabilityGuard` (candidate/round
schedulability - originally a private method inside `SlotFinderService`, now used by both
find-slots and booking, since a slot search and the eventual booking attempt can be minutes or
days apart and both need the same check re-run) and `ParticipantRoleResolver` (the same
`User.Role` → `ParticipantRole` mapping, including the `HIRING_MANAGER` gap noted in Phase 11).

**Verified live** against the real Supabase database with real HTTP requests: a non-staff
caller got `403`; a valid booking returned `200` with `SCHEDULED`/scheduled times/a calendar
event id, and direct SQL confirmed every side effect actually landed — `interview_rounds`
updated, two `interview_participants` rows (`CANDIDATE`+`INTERVIEWER`, both `ASSIGNED`), one
`calendar_events` row (`PENDING`), two `notifications` rows (`INTERVIEW_SCHEDULED`, `PENDING`),
one `audit_logs` row with the right metadata, and exactly one `idempotency_keys` row
(`COMPLETED`); retrying the identical request with the same idempotency key returned a
byte-identical response and created **no** additional rows anywhere (confirmed against the
same tables); a new idempotency key against the now-`SCHEDULED` round correctly got `409`
("not in a schedulable state") instead of double-booking; an inverted time range got `422`.
**Not verified**: true concurrent request races (this session drives the API sequentially, one
request at a time) — the locking strategy is code-reviewed and explained above, not exercised
under real concurrency. Worth a genuine concurrent-client test before relying on it in
production.

## Normal Recruiter Scheduling (Phase 13)

Unlike every phase before it, Phase 13 adds **no new code** — the spec itself frames it as
"connect all scheduling components," with no `Implement:` endpoint list of its own, just a
flow and a worked example. Every step of that flow already existed by the end of Phase 12
(identify candidate/job/current round -> Phase 7; requirements/eligible interviewer -> Phase
8; availability -> Phase 9; conflicts/working hours -> Phase 10; slots/ranking/recommendation
-> Phase 11; fresh validation/booking/calendar/notifications/audit -> Phase 12) and "recruiter
confirmation" is just the human step between calling `/recommend` and calling `/book` — not a
third endpoint. So this phase's actual deliverable is proof that the wiring holds end-to-end,
not new code.

**Verified live** against the real Supabase database by replaying the spec's own example
almost exactly — *"Schedule \[a candidate\]'s Java technical interview next week for 60
minutes, prefer afternoon, exclude Friday, and ensure \[an interviewer\] is available"* —
through nothing but the deterministic API, using a fresh candidate/job/process to avoid any
interference from earlier phases' test data:

1. Registered a new candidate, gave them a `Java` skill, created a job requiring it, started
   an interview process (4 fresh `PENDING` rounds).
2. Booked and passed the `SCREENING` round first (exercising Phase 7's result/progression
   logic for real, not just in isolation) — confirmed the candidate's `currentStatus` advanced
   to `TECHNICAL` and `currentRoundId` moved to the technical round, which is what makes that
   round schedulable at all (Phase 11/12's `SchedulabilityGuard` checks exactly this
   dependency).
3. Set the candidate's and interviewer's availability for a weekday next week *and* for the
   Friday of that same week (deliberately, to prove exclusion actually filters something out
   rather than trivially having nothing to exclude).
4. Called `/recommend` with `preferredInterviewerId` (the interviewer), `durationMinutes: 60`,
   `preferredTimeStart/End` covering the afternoon, and `excludedDays: ["FRIDAY"]` — got back
   three ranked Monday-afternoon slots, the Friday availability correctly absent, and a
   genuinely explainable `reason` on the top slot: *"Skill/domain match score 10.0; preferred
   interviewer; within preferred time window; 7.9 day(s) out."*
5. "Recruiter confirmation": booked the top-ranked slot exactly as returned, with no
   modification — `200`, `SCHEDULED`, a calendar event id, matching Phase 12's already-verified
   side effects.

Every step returned exactly what the spec's worked example describes, using only IDs a
recruiter's UI would already have on hand (candidate id, round id, interviewer id) — turning
the literal English sentence in the example into those specific API calls is Phase 26's job
(the agentic AI layer), not this one's; Phase 11 already noted "feasibility is deterministic,
AI only ranks/explains later," and this phase is the proof that the deterministic side of that
split actually works as one connected pipeline.

## Reusable Rescheduling Engine (Phase 14)

`InterviewReschedulingService` (in the `interview` package, since it manages `InterviewRound`
state transitions the same way `InterviewProcessService` does, even though it calls into
`scheduling` for the actual search) implements the two endpoints and is written to be a
seam other phases plug into rather than duplicate:

- **`POST /api/interviews/{id}/reschedule`** (self-or-staff, like Availability) — the reusable
  flow: validate reschedulable state (`SCHEDULED` or already `RESCHEDULE_REQUIRED`) and the
  `maximum_reschedules` cap, capture the round's *current* interviewer (as a soft preference,
  not a hard requirement — the whole point of re-searching is that the original interviewer
  might be exactly who's unavailable now) and any other already-assigned participants, cancel
  the existing calendar event, null the round's `scheduledStart`/`scheduledEnd` and move it to
  `RESCHEDULE_REQUIRED`, increment `reschedule_count`, notify + audit, then hand off to Phase
  11/12's own `SchedulingService.recommend` — the *exact same* find → rank → recommend
  pipeline a fresh scheduling request would use, not a parallel copy of it. Candidate stage
  never changes here (unlike a PASS/FAIL result) — only the round's own schedule state moves,
  which is what makes this one method reusable across every trigger the spec lists by name
  (interviewer cancellation, a recruiter or candidate wanting a different time, a calendar
  conflict/sync issue, a decline): none of them touch the pipeline stage, only the round.
  Phase 15's interviewer-cancel and Phase 17's decline handling are expected to call this
  same method rather than re-implement the flow.
- **`POST /api/interviews/{id}/cancel`** (RECRUITER/ADMIN) — "recruiter intentional
  cancellation" specifically (Phase 17's phrase for it): `CANCELLED`, calendar event cancelled,
  participants marked `REMOVED` (not left `ASSIGNED` — a cancelled round shouldn't keep
  counting against an interviewer's Phase 8 workload score, which reads exactly that status),
  notify + audit. No automatic rescheduling — that's `reschedule`'s job, deliberately kept
  separate per Phase 17's own distinction between the two.

New: `reschedule_count` on `interview_rounds` (migration V19) — nothing before this phase
needed to persist how many times a round had been moved, so there was nowhere to check
`maximum_reschedules` against.

**Verified live** against the real Supabase database with real HTTP requests: an unrelated
candidate got `403` rescheduling someone else's round; the owning candidate successfully
rescheduled their own `TECHNICAL` round and got back ranked slots with the original
interviewer correctly preferred; direct SQL confirmed the old calendar event went `CANCELLED`,
`scheduled_start`/`scheduled_end` went `null`, `timezone` was preserved as the search default,
and both participants got an `INTERVIEW_RESCHEDULED` notification without losing their
`ASSIGNED` status; re-booking the new slot correctly returned the round to `SCHEDULED`; cycling
through reschedule → book two more times hit `reschedule_count = 3` (the configured
`maximum_reschedules`) and a further attempt correctly returned `409`; omitting `dateFrom`
correctly defaulted to searching from today. Separately, cancelling a different round
correctly produced `CANCELLED`, a cancelled calendar event, both participants `REMOVED`, an
`INTERVIEW_CANCELLED` notification/audit row, a `403` for a non-staff caller, and a `409` on a
second cancel attempt. No bugs found this round.

## Google OAuth + Calendar + Meet (Phase 20)

Phase 19 shipped `GoogleCalendarProvider` as a structured stub that always threw
`CalendarIntegrationException` ("not yet wired with real OAuth credentials"). Phase 20 replaces
those TODO blocks with real Google Calendar API v3 calls and adds the OAuth2 plumbing needed to
get an access token in the first place — new Maven dependencies (`google-api-client`,
`google-oauth-client`, `google-api-services-calendar`, `google-http-client-gson`; no version
was on the classpath already, so exact current Maven Central coordinates were looked up rather
than guessed) and a new `google_oauth_tokens` table (migration V20).

**One org-wide connection, not one per user.** An ADMIN authorizes *one* Google account once;
every interview event is created on that account's `primary` calendar, with every
candidate/interviewer/recruiter invited as an attendee by email address — they never need their
own Google OAuth connection. This matches Phase 19's own design (its TODO comments already
called the calendar `"primary"`, singular) and keeps the flow to what a hackathon/demo setting
can actually complete: one Google Cloud OAuth client, one consent screen click.

- **`GoogleOAuthService`** drives the authorization-code handshake:
  `GET /api/integrations/google-calendar/authorize` (ADMIN) returns Google's consent URL, with
  a one-time `state` value (an in-memory map, not a DB table — this is a short-lived CSRF
  handshake, not data that needs to survive a restart) tying the eventual callback back to
  whoever clicked it. `GET /api/integrations/google-calendar/callback` is the only endpoint in
  the whole API that's genuinely public (`SecurityConfig` `permitAll`s it) — Google redirects
  the admin's *browser* here directly with no way to attach our JWT, so the `state` round-trip
  is the CSRF defense instead. `prompt=consent` is forced on every authorization URL so Google
  always issues a `refresh_token`, even on a re-authorization (Google otherwise only grants one
  on a account's *first-ever* consent for this app).
- **`GoogleOAuthTokenService`** persists the single connection row and is the only thing that
  knows about token refresh: `getValidAccessToken()` is called before every Calendar API call
  and transparently refreshes if the stored token is within 60 seconds of expiring, so
  `GoogleCalendarProvider` never has to think about token lifecycle.
- **`GoogleCalendarProvider`** now does real work: `createEvent` requests a Google Meet
  conference (`conferenceData` + `hangoutsMeet` solution key, `conferenceDataVersion=1`) and
  returns the real `hangoutLink`; `updateEvent` patches an existing event; `cancelEvent` deletes
  one and treats a 404 (already gone at Google) as success, per the interface's documented
  contract. Every write uses `sendUpdates("none")` — this system already has its own
  `NotificationService` (Phase 22) as the single source of truth for who was told what, so
  Google is deliberately not asked to also email attendees directly.
- **`GET /api/integrations/google-calendar/status`** (RECRUITER/ADMIN) reports whether a
  connection exists, who connected it, and its token expiry — enough to confirm the setup
  without needing DB access.

**Known gap, carried from Phase 19, stated explicitly rather than silently fixed:** nothing in
the booking/rescheduling orchestration (Phases 12–17) calls `updateEvent` — a reschedule or
interviewer switch always cancels the old calendar event and creates a fresh one, because the
new slot can land on a different interviewer or participant list, not just a different time on
the same attendees. `updateEvent` is implemented correctly here and ready for a caller, but
re-plumbing four already-verified phases to prefer an in-place update is a separate decision,
not one to make as a side effect of this phase.

**A real bug, found by live-testing against this exact new code path, not left for later:**
the first live booking attempt with `calendar.provider=google` (Google Calendar not yet
connected) returned `500 Unhandled exception ... UnexpectedRollbackException: Transaction
silently rolled back because it has been marked as rollback-only` — instead of the booking
succeeding with the calendar event marked `FAILED`, which is the explicit Phase 12 contract
("calendar failure never rolls back booking"). Root cause: `GoogleOAuthTokenService
.getValidAccessToken()` was `@Transactional` with the default `REQUIRED` propagation, so when
it threw (no connection yet), it joined and poisoned the *same physical transaction* as the
enclosing `InterviewBookingService.book()` call — Spring's transactional advice marks a
transaction rollback-only the instant an unchecked exception crosses *any* `@Transactional`
method boundary on it, regardless of whether something further up the call stack (here,
`CalendarSyncService.syncCreate`'s `catch (CalendarIntegrationException)`) goes on to swallow
it. The booking transaction itself never threw anything — it just found itself unable to
commit. Fixed by switching `getValidAccessToken()` and `forceRefreshAccessToken()` to
`Propagation.REQUIRES_NEW`, so a thrown exception only rolls back this method's own (suspended,
independent) transaction and never touches the caller's. Re-ran the identical booking
afterward: `200 SCHEDULED`, with the `calendar_events` row correctly `FAILED` and an
`audit_logs` `CALENDAR_SYNC_FAILED` row recorded — exactly the intended behavior.

**Verified live** against the real Supabase database with real HTTP requests, using a fresh
candidate/job/process/interviewer (seeded via direct SQL for the interviewer profile — there is
still no `POST /api/interviewers` endpoint, per Phase 8's own note that profiles are provisioned
outside the API):
- `GET /authorize` as a non-ADMIN correctly `403`s; as ADMIN with no `GOOGLE_CLIENT_ID`/
  `GOOGLE_CLIENT_SECRET`/`GOOGLE_REDIRECT_URI` configured, correctly `502`s with a clear
  "Google OAuth is not configured" message rather than a stack trace.
- `GET /callback` with no `Authorization` header at all still reaches the handler (confirms
  `SecurityConfig`'s `permitAll` is scoped correctly) and fails on the same "not configured"
  check, not a `401`.
- `GET /status` correctly reports `{"connected": false, "activeProvider": "noop"}` before any
  connection exists, and enforces RECRUITER/ADMIN-only (`403` for a CANDIDATE).
- With `calendar.provider=google` active (no OAuth credentials available in this environment to
  complete a real consent screen — the same constraint Phase 19 documented), a booking against
  the round above surfaced the `UnexpectedRollbackException` bug above; after the fix, the
  identical request returned `200 SCHEDULED` with `calendar_events.status = FAILED` and
  `provider = GOOGLE`, and cancelling that same round afterward (exercising `cancelEvent`'s
  error path through the same fixed seam) also completed cleanly.
- **Not verified**: an actual end-to-end OAuth consent screen + token exchange + real Google
  Meet link creation, since no `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET` pair was available in
  this environment (same limitation Phase 19 flagged). Everything up to that boundary — the
  authorization URL construction, state validation, RBAC, and every code path a failed/absent
  connection exercises — was verified for real; running the actual consent flow once real
  Google Cloud OAuth credentials are provisioned is the one remaining step before relying on
  this in production.

## Deployment Preparation (later)

Target: Spring Boot on Render, PostgreSQL on Supabase, frontend on Vercel. No deployment
config is added yet — this is noted here so environment-variable naming stays consistent
with that target from the start.
