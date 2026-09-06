package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 28 — AI: confirmation, backend refusal. {@code POST /api/ai/schedule}'s own
 * parsing/tool-sequence/no-candidate/no-slot behavior needs a real {@code AI_API_KEY} (see
 * {@code AiOrchestrationService}) and isn't exercised here - see the README's Phase 26/27
 * section for what was verified live against the real Anthropic API instead. Everything
 * downstream of a proposal - the part that actually matters for safety - is fully exercised:
 * {@code AiActionExecutor} runs the exact same backend services the plain REST API uses.
 */
class AiConfirmIntegrationTest extends AbstractIntegrationTest {

    @Test
    void schedule_asCandidate_isForbidden() {
        Session candidate = registerAndLogin("Ai Rbac Candidate", "CANDIDATE");
        ResponseEntity<String> resp = post("/api/ai/schedule", candidate, obj().put("message", "test"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void confirm_asCandidate_isForbidden() {
        Session candidate = registerAndLogin("Ai Confirm Rbac Candidate", "CANDIDATE");
        ResponseEntity<String> resp = post("/api/ai/confirm", candidate, obj()
                .put("idempotencyKey", "x")
                .set("action", obj().put("type", "CANCEL_INTERVIEW").put("roundId", UUID.randomUUID().toString())));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void confirm_bookInterview_executesTheRealBackendBooking() {
        Session recruiter = registerAndLogin("Ai Confirm Book Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Ai Confirm Book Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Ai Confirm Book Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, "Ai Confirm Book Interviewer");

        LocalDate date = nextWeekday(10);
        JsonNode candidate = json(get("/api/candidates/" + candidateId, recruiter));
        Session candidateSession = new Session(
                json(login(candidate.get("email").asText(), PASSWORD)).get("token").asText(),
                UUID.fromString(candidate.get("userId").asText()), candidate.get("email").asText());
        setAvailability(candidateSession, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(interviewer.session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        ResponseEntity<String> resp = post("/api/ai/confirm", recruiter, obj()
                .put("idempotencyKey", "ai-confirm-it-book-" + roundId)
                .set("action", obj().put("type", "BOOK_INTERVIEW")
                        .put("roundId", roundId.toString())
                        .put("interviewerId", interviewer.profileId().toString())
                        .put("start", date + "T10:00:00+05:30")
                        .put("end", date + "T11:00:00+05:30")
                        .put("timezone", "Asia/Kolkata")));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(resp);
        assertThat(body.get("success").asBoolean()).isTrue();
        assertThat(body.get("actionType").asText()).isEqualTo("BOOK_INTERVIEW");
        assertThat(body.get("result").get("status").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void confirm_bookInterview_missingRequiredFields_isRejected() {
        Session recruiter = registerAndLogin("Ai Confirm BadBook Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Ai Confirm BadBook Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Ai Confirm BadBook Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        // A hallucinated/incomplete proposal - no interviewerId/start/end/timezone.
        ResponseEntity<String> resp = post("/api/ai/confirm", recruiter, obj()
                .put("idempotencyKey", "ai-confirm-bad-" + roundId)
                .set("action", obj().put("type", "BOOK_INTERVIEW").put("roundId", roundId.toString())));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void confirm_cancelOnAnAlreadyCancelledRound_isRefusedAndExplained() {
        Session recruiter = registerAndLogin("Ai Confirm Refusal Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Ai Confirm Refusal Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Ai Confirm Refusal Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        // Round is still PENDING (never booked) - cancel is allowed once, then a second cancel
        // must be refused, exactly like a direct REST call would be.
        assertThat(post("/api/interviews/" + roundId + "/cancel", recruiter, "").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = post("/api/ai/confirm", recruiter, obj()
                .put("idempotencyKey", "ai-confirm-refusal-" + roundId)
                .set("action", obj().put("type", "CANCEL_INTERVIEW").put("roundId", roundId.toString())));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(resp).get("message").asText()).containsIgnoringCase("cancel");
    }

    @Test
    void confirm_advanceCandidateToNextRound_executesTheRealProgression() {
        Session recruiter = registerAndLogin("Ai Confirm Advance Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Ai Confirm Advance Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Ai Confirm Advance Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, "Ai Confirm Advance Interviewer");

        LocalDate date = nextWeekday(10);
        JsonNode candidate = json(get("/api/candidates/" + candidateId, recruiter));
        Session candidateSession = new Session(
                json(login(candidate.get("email").asText(), PASSWORD)).get("token").asText(),
                UUID.fromString(candidate.get("userId").asText()), candidate.get("email").asText());
        setAvailability(candidateSession, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(interviewer.session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        assertThat(bookInterview(recruiter, roundId, interviewer.profileId(), date + "T10:00:00+05:30",
                date + "T11:00:00+05:30", "ai-advance-book-" + roundId).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = post("/api/ai/confirm", recruiter, obj()
                .put("idempotencyKey", "ai-advance-" + roundId)
                .set("action", obj().put("type", "ADVANCE_CANDIDATE_TO_NEXT_ROUND").put("roundId", roundId.toString())));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode candidateAfter = json(get("/api/candidates/" + candidateId, recruiter));
        assertThat(candidateAfter.get("currentStatus").asText()).isEqualTo("TECHNICAL");
    }
}
