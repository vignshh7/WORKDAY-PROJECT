package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewscheduler.integration.CalendarEvent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 28 — SCHEDULING: availability, no common slot, calendar conflict, buffer, working hours,
 * timezone, notice period. BOOKING: success, duplicate, stale slot, concurrency, unauthorized.
 */
class SchedulingAndBookingIntegrationTest extends AbstractIntegrationTest {

    private record Setup(Session recruiter, UUID candidateId, UUID roundId, InterviewerFixture interviewer) {
    }

    private Setup freshSetup(String namePrefix) {
        Session recruiter = registerAndLogin(namePrefix + " Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, namePrefix + " Candidate");
        UUID jobId = createOpenJavaJob(recruiter, namePrefix + " Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, namePrefix + " Interviewer");
        return new Setup(recruiter, candidateId, roundId, interviewer);
    }

    private Session candidateSessionOf(Session recruiter, UUID candidateId) {
        JsonNode candidate = json(get("/api/candidates/" + candidateId, recruiter));
        String email = candidate.get("email").asText();
        ResponseEntity<String> loginResp = login(email, PASSWORD);
        return new Session(json(loginResp).get("token").asText(),
                UUID.fromString(candidate.get("userId").asText()), email);
    }

    // -------------------------------------------------------------------------
    // Scheduling: reason codes and conflict detection
    // -------------------------------------------------------------------------

    @Test
    void recommend_withNoCandidateAvailability_returnsThatReasonCode() {
        Setup s = freshSetup("NoAvail");
        LocalDate date = nextWeekday(10);
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        // Candidate has no availability at all.

        ResponseEntity<String> resp = post("/api/scheduling/recommend", s.recruiter(), obj()
                .put("candidateId", s.candidateId().toString())
                .put("roundId", s.roundId().toString())
                .put("durationMinutes", 60)
                .put("dateFrom", date.toString())
                .put("dateTo", date.toString())
                .put("timezone", "Asia/Kolkata"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.get("slots")).isEmpty();
        assertThat(body.get("reasonCode").asText()).isEqualTo("NO_CANDIDATE_AVAILABILITY");
    }

    @Test
    void checkConflicts_outsideWorkingHours_isFlagged() {
        Setup s = freshSetup("WorkHours");
        LocalDate date = nextWeekday(10);
        LocalTime beforeOpen = workingHoursService.currentConfig().getWorkingStart().minusHours(2);
        OffsetDateTime start = date.atTime(beforeOpen).atOffset(java.time.ZoneOffset.of("+05:30"));
        OffsetDateTime end = start.plusMinutes(60);

        ResponseEntity<String> resp = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", s.roundId().toString())
                .put("start", start.toString())
                .put("end", end.toString()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(conflictTypes(resp)).contains("WORKING_HOURS_CONFLICT");
    }

    @Test
    void checkConflicts_withinNoticePeriod_isFlagged() {
        Setup s = freshSetup("Notice");
        OffsetDateTime start = OffsetDateTime.now().plusMinutes(5);
        OffsetDateTime end = start.plusMinutes(60);

        ResponseEntity<String> resp = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", s.roundId().toString())
                .put("start", start.toString())
                .put("end", end.toString()));

        assertThat(conflictTypes(resp)).contains("NOTICE_PERIOD_CONFLICT");
    }

    @Test
    void checkConflicts_invalidTimeRange_isFlagged() {
        Setup s = freshSetup("InvalidRange");
        OffsetDateTime start = OffsetDateTime.now().plusDays(10);
        OffsetDateTime end = start.minusMinutes(30);

        ResponseEntity<String> resp = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", s.roundId().toString())
                .put("start", start.toString())
                .put("end", end.toString()));

        assertThat(conflictTypes(resp)).contains("INVALID_TIME_RANGE");
    }

    @Test
    void bookingThenCheckingOverlap_producesInterviewerAndBufferConflicts() {
        Setup s = freshSetup("Overlap");
        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(s.recruiter(), s.candidateId()), date, LocalTime.of(10, 0), LocalTime.of(14, 0));
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(14, 0));

        String start = date + "T10:00:00+05:30";
        String end = date + "T11:00:00+05:30";
        ResponseEntity<String> bookResp = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                start, end, "overlap-book-" + s.roundId());
        assertThat(bookResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second, different round for a second candidate, checked against the SAME interviewer,
        // exactly overlapping the first booking - a real double-booking scenario.
        UUID candidate2 = createCandidateWithJavaSkill(s.recruiter(), "Overlap Candidate 2");
        UUID job2 = createOpenJavaJob(s.recruiter(), "Overlap Role 2");
        UUID round2 = createProcessAndGetCurrentRoundId(s.recruiter(), candidate2, job2);

        ResponseEntity<String> exactOverlap = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", round2.toString())
                .put("start", date + "T10:00:00+05:30")
                .put("end", date + "T11:00:00+05:30")
                .put("interviewerId", s.interviewer().profileId().toString()));
        assertThat(conflictTypes(exactOverlap)).contains("INTERVIEWER_CONFLICT");

        // Adjacent with a small gap inside the configured buffer, but not literally overlapping.
        int buffer = workingHoursService.currentConfig().getDefaultBufferMinutes();
        if (buffer > 0) {
            String bufferStart = OffsetDateTime.parse(date + "T11:00:00+05:30").plusMinutes(Math.max(1, buffer / 2)).toString();
            String bufferEnd = OffsetDateTime.parse(bufferStart).plusMinutes(60).toString();
            ResponseEntity<String> bufferOverlap = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                    .put("roundId", round2.toString())
                    .put("start", bufferStart)
                    .put("end", bufferEnd)
                    .put("interviewerId", s.interviewer().profileId().toString()));
            assertThat(conflictTypes(bufferOverlap)).contains("BUFFER_CONFLICT");
        }

        // Comfortably clear of both the meeting and its buffer - no conflict.
        String clearStart = date + "T13:00:00+05:30";
        String clearEnd = date + "T13:30:00+05:30";
        ResponseEntity<String> clear = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", round2.toString())
                .put("start", clearStart)
                .put("end", clearEnd)
                .put("interviewerId", s.interviewer().profileId().toString()));
        assertThat(json(clear).get("hasConflicts").asBoolean()).isFalse();
    }

    @Test
    void checkConflicts_crossTimezoneOverlap_isDetectedByRealInstant() {
        Setup s = freshSetup("Timezone");
        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(s.recruiter(), s.candidateId()), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        // Booked at 10:00-11:00 +05:30 (IST).
        ResponseEntity<String> bookResp = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", "tz-book-" + s.roundId());
        assertThat(bookResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID candidate2 = createCandidateWithJavaSkill(s.recruiter(), "Timezone Candidate 2");
        UUID job2 = createOpenJavaJob(s.recruiter(), "Timezone Role 2");
        UUID round2 = createProcessAndGetCurrentRoundId(s.recruiter(), candidate2, job2);

        // 04:30-05:30 UTC is the exact same instant as 10:00-11:00 +05:30 - different offset,
        // same wall-clock overlap once compared correctly.
        ResponseEntity<String> resp = post("/api/scheduling/check-conflicts", s.recruiter(), obj()
                .put("roundId", round2.toString())
                .put("start", date + "T04:30:00+00:00")
                .put("end", date + "T05:30:00+00:00")
                .put("interviewerId", s.interviewer().profileId().toString()));
        assertThat(conflictTypes(resp)).contains("INTERVIEWER_CONFLICT");
    }

    // -------------------------------------------------------------------------
    // Booking
    // -------------------------------------------------------------------------

    @Test
    void booking_success_createsCalendarEventAndNotifications() {
        Setup s = freshSetup("BookSuccess");
        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(s.recruiter(), s.candidateId()), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        ResponseEntity<String> resp = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", "book-success-" + s.roundId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.get("status").asText()).isEqualTo("SCHEDULED");

        UUID calendarEventId = UUID.fromString(body.get("calendarEventId").asText());
        CalendarEvent event = calendarEventRepository.findById(calendarEventId).orElseThrow();
        assertThat(event.getStatus().name()).isEqualTo("CREATED");

        long sentNotifications = notificationRepository.findByInterviewRoundId(s.roundId()).stream()
                .filter(n -> n.getStatus().name().equals("SENT"))
                .count();
        assertThat(sentNotifications).isGreaterThanOrEqualTo(2); // candidate + interviewer
    }

    @Test
    void booking_retriedWithSameIdempotencyKey_returnsSameResultWithoutDuplicating() {
        Setup s = freshSetup("Idempotent");
        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(s.recruiter(), s.candidateId()), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        String key = "idempotent-" + s.roundId();

        ResponseEntity<String> first = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        int eventsAfterFirst = calendarEventRepository.findByInterviewRoundId(s.roundId()).size();

        ResponseEntity<String> replay = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(calendarEventRepository.findByInterviewRoundId(s.roundId())).hasSize(eventsAfterFirst);
    }

    @Test
    void booking_withoutCandidateAvailability_isRejectedAsStale() {
        Setup s = freshSetup("Stale");
        LocalDate date = nextWeekday(10);
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        // Candidate never set availability for this window - a "stale slot" from the caller's perspective.

        ResponseEntity<String> resp = bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", "stale-" + s.roundId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void booking_asCandidate_isUnauthorized() {
        Setup s = freshSetup("Unauthorized");
        LocalDate date = nextWeekday(10);
        Session candidateSession = candidateSessionOf(s.recruiter(), s.candidateId());

        ResponseEntity<String> resp = bookInterview(candidateSession, s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", "unauth-" + s.roundId());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void booking_concurrentRequestsForTheSameRound_onlyOneSucceeds() throws Exception {
        Setup s = freshSetup("Concurrency");
        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(s.recruiter(), s.candidateId()), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(s.interviewer().session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<String>>> futures = List.of(
                    pool.submit(() -> raceBook(startGate, s, date, "race-a-" + s.roundId())),
                    pool.submit(() -> raceBook(startGate, s, date, "race-b-" + s.roundId())));
            startGate.countDown();

            int successCount = 0;
            for (Future<ResponseEntity<String>> f : futures) {
                ResponseEntity<String> resp = f.get(30, TimeUnit.SECONDS);
                if (resp.getStatusCode() == HttpStatus.OK) {
                    successCount++;
                } else {
                    assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
                }
            }
            assertThat(successCount).isEqualTo(1);
            assertThat(calendarEventRepository.findByInterviewRoundId(s.roundId())).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<String> raceBook(CountDownLatch startGate, Setup s, LocalDate date, String key) {
        try {
            startGate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return bookInterview(s.recruiter(), s.roundId(), s.interviewer().profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", key);
    }

    private List<String> conflictTypes(ResponseEntity<String> resp) {
        JsonNode conflicts = json(resp).get("conflicts");
        return conflicts == null ? List.of() : java.util.stream.StreamSupport
                .stream(conflicts.spliterator(), false)
                .map(n -> n.get("conflictType").asText())
                .toList();
    }
}
