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
 * Phase 28 — CANDIDATE/JOB: CRUD, duplicate skills, invalid states.
 * PIPELINE: valid progression, invalid progression, PASS, FAIL, HOLD, future-round invalidation.
 */
class CandidateJobPipelineIntegrationTest extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // Candidate / job CRUD + duplicate skills + invalid states
    // -------------------------------------------------------------------------

    @Test
    void addingTheSameCandidateSkillTwice_returns409() {
        Session recruiter = registerAndLogin("Dup Skill Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Dup Skill Candidate");

        ResponseEntity<String> resp = post("/api/candidates/" + candidateId + "/skills", recruiter,
                obj().put("skillId", JAVA_SKILL_ID).put("proficiency", 3).put("yearsExperience", 1));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void addingTheSameJobSkillTwice_returns409() {
        Session recruiter = registerAndLogin("Dup Job Skill Recruiter", "RECRUITER");
        UUID jobId = createOpenJavaJob(recruiter, "Dup Job Skill Role");

        ResponseEntity<String> resp = post("/api/jobs/" + jobId + "/skills", recruiter,
                obj().put("skillId", JAVA_SKILL_ID).put("required", true).put("weight", 1.0).put("minimumProficiency", 3));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createCandidate_invalidPayload_returns400() {
        Session recruiter = registerAndLogin("Invalid Candidate Recruiter", "RECRUITER");
        // userId is required (@NotNull) - omit it entirely.
        ResponseEntity<String> resp = post("/api/candidates", recruiter, obj().put("phone", "1234567890"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deletingAJobWithAnInterviewProcess_isRefused() {
        Session recruiter = registerAndLogin("Delete Job Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Delete Job Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Delete Job Role");
        createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        ResponseEntity<String> resp = exchangeDelete("/api/jobs/" + jobId, recruiter);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private ResponseEntity<String> exchangeDelete(String path, Session session) {
        return exchange(org.springframework.http.HttpMethod.DELETE, path, session, null);
    }

    // -------------------------------------------------------------------------
    // Pipeline progression
    // -------------------------------------------------------------------------

    @Test
    void creatingASecondActiveProcessForTheSameCandidate_returns409() {
        Session recruiter = registerAndLogin("Dup Process Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Dup Process Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Dup Process Role");
        createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        UUID job2 = createOpenJavaJob(recruiter, "Dup Process Role 2");
        ResponseEntity<String> resp = post("/api/interview-processes", recruiter,
                obj().put("candidateId", candidateId.toString()).put("jobId", job2.toString()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void completingARound_beforeItIsScheduled_isRejected() {
        Session recruiter = registerAndLogin("Complete Early Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Complete Early Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Complete Early Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        ResponseEntity<String> resp = post("/api/interviews/" + roundId + "/complete", recruiter, "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void passResult_advancesCandidateToNextRound() {
        Session recruiter = registerAndLogin("Pass Path Recruiter", "RECRUITER");
        BookedRound booked = bookFreshCandidateThroughScreening(recruiter, "Pass Path");

        assertThat(post("/api/interviews/" + booked.roundId + "/complete", recruiter, "").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resultResp = post("/api/interviews/" + booked.roundId + "/result", recruiter,
                obj().put("result", "PASS"));
        assertThat(resultResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> candidateResp = get("/api/candidates/" + booked.candidateId, recruiter);
        JsonNode candidate = json(candidateResp);
        assertThat(candidate.get("currentStatus").asText()).isEqualTo("TECHNICAL");
        assertThat(candidate.get("currentRoundId").asText()).isNotEqualTo(booked.roundId.toString());
    }

    @Test
    void submittingAResultTwiceOnTheSameRound_isRejected() {
        Session recruiter = registerAndLogin("Double Result Recruiter", "RECRUITER");
        BookedRound booked = bookFreshCandidateThroughScreening(recruiter, "Double Result");
        post("/api/interviews/" + booked.roundId + "/complete", recruiter, "");
        post("/api/interviews/" + booked.roundId + "/result", recruiter, obj().put("result", "PASS"));

        ResponseEntity<String> secondResult = post("/api/interviews/" + booked.roundId + "/result", recruiter,
                obj().put("result", "PASS"));
        assertThat(secondResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void holdResult_doesNotProgressTheCandidate() {
        Session recruiter = registerAndLogin("Hold Path Recruiter", "RECRUITER");
        BookedRound booked = bookFreshCandidateThroughScreening(recruiter, "Hold Path");
        post("/api/interviews/" + booked.roundId + "/complete", recruiter, "");

        ResponseEntity<String> resultResp = post("/api/interviews/" + booked.roundId + "/result", recruiter,
                obj().put("result", "HOLD"));
        assertThat(resultResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> candidateResp = get("/api/candidates/" + booked.candidateId, recruiter);
        JsonNode candidate = json(candidateResp);
        assertThat(candidate.get("currentStatus").asText()).isEqualTo("SCREENING");
        assertThat(candidate.get("currentRoundId").asText()).isEqualTo(booked.roundId.toString());
    }

    @Test
    void failResult_rejectsCandidateAndInvalidatesFutureRounds() {
        Session recruiter = registerAndLogin("Fail Path Recruiter", "RECRUITER");
        BookedRound booked = bookFreshCandidateThroughScreening(recruiter, "Fail Path");
        post("/api/interviews/" + booked.roundId + "/complete", recruiter, "");

        ResponseEntity<String> resultResp = post("/api/interviews/" + booked.roundId + "/result", recruiter,
                obj().put("result", "FAIL"));
        assertThat(resultResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> candidateResp = get("/api/candidates/" + booked.candidateId, recruiter);
        JsonNode candidate = json(candidateResp);
        assertThat(candidate.get("currentStatus").asText()).isEqualTo("REJECTED");

        JsonNode pipeline = json(get("/api/candidates/" + booked.candidateId + "/pipeline", recruiter));
        JsonNode rounds = pipeline.get(0).get("rounds");
        boolean anyFutureRoundStillPending = false;
        for (JsonNode round : rounds) {
            if (round.get("roundNumber").asInt() > 1) {
                String status = round.get("status").asText();
                assertThat(status).isEqualTo("CANCELLED");
                if (!status.equals("CANCELLED")) {
                    anyFutureRoundStillPending = true;
                }
            }
        }
        assertThat(anyFutureRoundStillPending).isFalse();
    }

    // -------------------------------------------------------------------------
    // Shared fixture: candidate + job + process + interviewer + availability + booked SCREENING round
    // -------------------------------------------------------------------------

    private record BookedRound(UUID candidateId, UUID roundId) {
    }

    private BookedRound bookFreshCandidateThroughScreening(Session recruiter, String namePrefix) {
        UUID candidateId = createCandidateWithJavaSkill(recruiter, namePrefix + " Candidate");
        UUID jobId = createOpenJavaJob(recruiter, namePrefix + " Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, namePrefix + " Interviewer");

        LocalDate date = nextWeekday(10);
        setAvailabilityForCandidateUser(recruiter, candidateId, date);
        setAvailability(interviewer.session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        String start = date + "T10:00:00+05:30";
        String end = date + "T11:00:00+05:30";
        ResponseEntity<String> bookResp = bookInterview(recruiter, roundId, interviewer.profileId(), start, end,
                "pipeline-test-" + roundId);
        assertThat(bookResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new BookedRound(candidateId, roundId);
    }

    /** Candidate availability must be set by the candidate themselves - re-login as that user. */
    private void setAvailabilityForCandidateUser(Session recruiter, UUID candidateId, LocalDate date) {
        JsonNode candidate = json(get("/api/candidates/" + candidateId, recruiter));
        String email = candidate.get("email").asText();
        ResponseEntity<String> loginResp = login(email, PASSWORD);
        Session candidateSession = new Session(json(loginResp).get("token").asText(),
                UUID.fromString(candidate.get("userId").asText()), email);
        setAvailability(candidateSession, date, LocalTime.of(10, 0), LocalTime.of(12, 0));
    }
}
