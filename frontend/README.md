# Interview Scheduler — Frontend

React + Vite + JavaScript + React Router + Axios + Tailwind. This is the UI for the
Spring Boot backend in [`../backend`](../backend); the endpoint-by-endpoint reference is
[`../backend/API_DOCUMENTATION.md`](../backend/API_DOCUMENTATION.md) and the live Swagger UI
is at `GET /swagger-ui.html` on the running backend. Nothing here duplicates that — read it
for request/response shapes.

## Running it

```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # production bundle into dist/
npm run lint     # eslint
```

The dev server proxies `/api` to `http://localhost:8080`, so the backend needs to be running.
Copy `.env.example` to `.env` to change either side:

| Variable | Meaning |
|---|---|
| `VITE_API_BASE_URL` | Full backend origin for a deployed build. Blank = same origin (uses the dev proxy). |
| `VITE_PROXY_TARGET` | Where the dev proxy forwards `/api`. Defaults to `http://localhost:8080`. |

For the Google Calendar round trip to land back in the UI, set `APP_BASE_URL=http://localhost:5173`
on the **backend** — its OAuth callback redirects the browser to
`{APP_BASE_URL}/settings/integrations`.

## The one architectural rule

**No business rules live in the frontend.** Whether a slot can be booked, whether a candidate
can advance, whether an interviewer qualifies — the backend decides all of it. This code calls
the API and renders what comes back, including refusals. Where the UI greys out an action (a
rejected candidate, a cancelled round), that's a clarity affordance layered on top of a check
the backend performs anyway, never a substitute for it.

The same principle drives the AI mode: `POST /api/ai/schedule` proposes, `POST /api/ai/confirm`
executes through the *same* services a manual booking uses. Both modes render slots with the
same `<SchedulingResults>` component — deliberately, so the demo shows that AI is a second door
to one deterministic engine, not a shortcut around it.

## Layout

```
src/
  api/          client.js (axios + interceptors), endpoints.js (one fn per endpoint)
  auth/         AuthContext.jsx, tokenStore.js
  components/   ui/ primitives, StatusBadge, SchedulingResults, Pipeline, NotificationBell
  constants/    enums.js (backend value sets verbatim), theme.js (badge tones)
  context/      ToastContext.jsx
  hooks/        useAsync.js (useFetch/useAction), useRounds.js
  layouts/      AppLayout.jsx
  pages/        auth, recruiter, interviewer, candidate, admin, settings
  routes/       guards.jsx (ProtectedRoute, RoleRoute, HomeRedirect)
  utils/        datetime, errors, format, idempotency
```

## Things worth knowing before you change anything

**The token** lives in memory with a `sessionStorage` mirror (`auth/tokenStore.js`) so a refresh
doesn't sign you out but closing the tab does. A `401` anywhere clears it and bounces to login. A
`403` does *not* — that's a role or ownership refusal, not a token problem, so it renders a
"not allowed" state and never retries.

**Errors are one shape.** Every backend error is `{timestamp, status, error, message, path}`, so
`utils/errors.js` parses it once and `toast.apiError(err)` handles it everywhere. Don't add
per-call error parsing.

**Empty ≠ error.** `POST /api/scheduling/recommend` returns `200` with an empty `slots` array and
a `reasonCode` when nothing is bookable. Each reason code has its own message in
`constants/enums.js`. Loading-empty, reason-code-empty and error-empty are three visually
distinct states — keep them that way.

**Idempotency keys.** `book`, `switch-interviewer` and `ai/confirm` take one UUID per *user
action*. `utils/idempotency.js` holds the key stable across retries of the same click. A fresh
key on retry can double-book when the first request succeeded but its response was lost, so
mutating calls are never auto-retried — retry is always the user re-clicking.

**Slots go stale.** The backend re-validates from scratch at booking time. A `422` on `book`
means someone took the slot; the UI shows "This slot was just taken. Here are the latest
available options." and re-runs `/recommend` automatically.

**Candidate status ≠ round status.** Two different fields from two different endpoints, with two
different vocabularies, rendered by two different components (`CandidateStatusBadge` vs
`RoundStatusBadge`). Cancelling or rescheduling a round never moves the candidate's pipeline
stage — only a PASS/FAIL result does.

## Backend gaps this UI works around

These are real missing pieces, surfaced in the UI rather than faked. Each would be a small
backend change.

| Gap | What the UI does |
|---|---|
| `InterviewRoundResponse` has no `interviewerId` | The interview page says the assigned interviewer isn't returned. It can't tell whether the viewer is the assigned interviewer, so interviewer actions are shown and the backend's own `403` enforces it. |
| No "my assigned interviews" endpoint | The interviewer dashboard reconstructs a list from their notifications (which carry `interviewRoundId`), and says so. |
| No `GET /api/interviews/{id}` | An interview is found by scanning candidate pipelines. Fine at demo scale; an aggregate endpoint would fix it. |
| No way to map a signed-in user to their candidate profile (`GET /api/candidates` is staff-only) | The candidate dashboard asks for the candidate ID once, verifies it, and keeps it in `localStorage`. The reason is stated on screen. |
| No read/unread tracking on notifications | "New since your last visit", computed against a `localStorage` timestamp per user. Deliberately not worded as an unread count. |
| No analytics or scheduling-config endpoints (Phase 25 skipped) | Admin panels that *can* be honestly computed client-side (candidate stages, interview status, rescheduling, users by role) are; interviewer utilization, scheduling success, conflicts and calendar failures are listed as unavailable with the reason. Nothing is fabricated. |
| No org-wide integration health | The admin panel scopes itself to the caller's own connection and says so. |
| No skill list/create endpoint | Skill IDs are pasted in, with a note that skills are seeded on the backend. |
| Meet link isn't on `BookingResponse` | The interview page points people at Google's own calendar invitation instead of showing a link it doesn't have. |
| No Google Calendar disconnect endpoint | Settings says to revoke from Google account settings rather than offering a button that does nothing. |
| Calendar-sync failure isn't surfaced on a booking | Not shown at all, because there's no field to read. Booking succeeds regardless by design. |

## Demo notes

- Register the candidate and interviewer **users first**, then attach profiles
  (`POST /api/candidates` / `POST /api/interviewers` need an existing user id).
- A candidate can only have one ACTIVE process at a time — a second one returns `409`.
- AI mode needs `AI_API_KEY` on the backend or `/api/ai/schedule` returns `500`. Form mode
  works without it and reaches the same engine, so it's the safe fallback at demo time.
- Real calendar invites and Meet links need `CALENDAR_PROVIDER=google` plus Google credentials
  on the backend, and the **interviewer** connected (the event is created on their calendar).
- To show the per-user calendar behaviour: have a candidate and interviewer both connect, put a
  personal event in one of their calendars, and watch that window disappear from the recommended
  slots even though no `Availability` row reflects it.
