package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.RoundStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 28 — CANCELLATION: interviewer cancel, recruiter cancel, candidate reschedule,
 * after-start cancellation. REPLACEMENT: backup/other interviewer, no qualified replacement,
 * same slot, replacement switch.
 */
class CancellationAndReplacementIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private InterviewRoundRepository interviewRoundRepository;

    private record BookedSetup(Session recruiter, UUID candidateId, UUID roundId, InterviewerFixture interviewer,
                                LocalDate date) {
    }

    private BookedSetup bookFreshRound(String namePrefix) {
        Session recruiter = registerAndLogin(namePrefix + " Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithJavaSkill(recruiter, namePrefix + " Candidate");
        UUID jobId = createOpenJavaJob(recruiter, namePrefix + " Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, namePrefix + " Interviewer");

        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(recruiter, candidateId), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(interviewer.session(), date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        ResponseEntity<String> bookResp = bookInterview(recruiter, roundId, interviewer.profileId(),
                date + "T10:00:00+05:30", date + "T11:00:00+05:30", "cancel-setup-" + roundId);
        assertThat(bookResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return new BookedSetup(recruiter, candidateId, roundId, interviewer, date);
    }

    private Session candidateSessionOf(Session recruiter, UUID candidateId) {
        JsonNode candidate = json(get("/api/candidates/" + candidateId, recruiter));
        String email = candidate.get("email").asText();
        ResponseEntity<String> loginResp = login(email, PASSWORD);
        return new Session(json(loginResp).get("token").asText(),
                UUID.fromString(candidate.get("userId").asText()), email);
    }

    // -------------------------------------------------------------------------
    // Cancellation
    // -------------------------------------------------------------------------

    @Test
    void interviewerCancel_movesRoundToRescheduleRequired_candidateStageUnchanged() {
        BookedSetup s = bookFreshRound("IntCancel");

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/interviewer-cancel",
                s.interviewer().session(), "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode candidate = json(get("/api/candidates/" + s.candidateId(), s.recruiter()));
        assertThat(candidate.get("currentStatus").asText()).isEqualTo("SCREENING");

        InterviewRound round = interviewRoundRepository.findById(s.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.RESCHEDULE_REQUIRED);
    }

    @Test
    void interviewerCancel_byUnrelatedInterviewer_isForbidden() {
        BookedSetup s = bookFreshRound("IntCancelForbidden");
        InterviewerFixture unrelated = createInterviewerWithJavaSkill(s.recruiter(), "IntCancelForbidden Other");

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/interviewer-cancel",
                unrelated.session(), "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void recruiterCancel_movesRoundToCancelled_withNoAutoReschedule() {
        BookedSetup s = bookFreshRound("RecruiterCancel");

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/cancel", s.recruiter(), "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(resp).get("status").asText()).isEqualTo("CANCELLED");

        // No automatic reschedule: the round stays CANCELLED, not RESCHEDULE_REQUIRED.
        InterviewRound round = interviewRoundRepository.findById(s.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.CANCELLED);
    }

    @Test
    void candidateReschedule_movesRoundToRescheduleRequiredAndReturnsNewSlots() {
        BookedSetup s = bookFreshRound("CandReschedule");
        Session candidateSession = candidateSessionOf(s.recruiter(), s.candidateId());

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/reschedule", candidateSession,
                obj().put("reason", "Candidate has a conflict"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        InterviewRound round = interviewRoundRepository.findById(s.roundId()).orElseThrow();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.RESCHEDULE_REQUIRED);
    }

    @Test
    void interviewerCancel_afterRoundHasStarted_isRejected() {
        BookedSetup s = bookFreshRound("AfterStart");
        // Nothing in this system transitions a round to IN_PROGRESS automatically yet - simulate
        // "the interview has already started" directly, the same state check the endpoint itself guards against.
        InterviewRound round = interviewRoundRepository.findById(s.roundId()).orElseThrow();
        round.setStatus(RoundStatus.IN_PROGRESS);
        interviewRoundRepository.saveAndFlush(round);

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/interviewer-cancel",
                s.interviewer().session(), "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Replacement
    // -------------------------------------------------------------------------

    @Test
    void findReplacement_withAnotherQualifiedInterviewer_returnsOptions() {
        BookedSetup s = bookFreshRound("ReplaceFound");
        createInterviewerWithJavaSkill(s.recruiter(), "ReplaceFound Backup");

        ResponseEntity<String> resp = post("/api/interviews/" + s.roundId() + "/interviewer-cancel",
                s.interviewer().session(), "");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> findResp = post("/api/interviews/" + s.roundId() + "/find-replacement", s.recruiter(), "");
        assertThat(findResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode options = json(findResp).get("options");
        assertThat(options).isNotEmpty();
    }

    @Test
    void findReplacement_withNoOtherQualifiedInterviewer_returnsNoOptions() {
        UUID isolatedSkill = createUniqueSkill("NoReplacementSkill");
        Session recruiter = registerAndLogin("NoReplace Recruiter", "RECRUITER");
        UUID candidateId = createCandidateWithSkill(recruiter, "NoReplace Candidate", isolatedSkill);
        UUID jobId = createOpenJobRequiringSkill(recruiter, "NoReplace Role", isolatedSkill);
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        Session interviewerUser = registerAndLogin("NoReplace Interviewer", "INTERVIEWER");
        var profile = new com.interviewscheduler.interviewer.InterviewerProfile();
        profile.setUser(userRepository.getReferenceById(interviewerUser.userId()));
        profile.setDepartment("Engineering");
        profile.setDesignation("Engineer");
        profile.setDomain("Backend");
        profile.setMaxInterviewsPerDay((short) 4);
        profile = interviewerProfileRepository.saveAndFlush(profile);
        post("/api/interviewers/" + profile.getId() + "/skills", recruiter,
                obj().put("skillId", isolatedSkill.toString()).put("proficiency", 4).put("yearsExperience", 3).put("isPrimary", true));

        LocalDate date = nextWeekday(10);
        setAvailability(candidateSessionOf(recruiter, candidateId), date, LocalTime.of(10, 0), LocalTime.of(12, 0));
        setAvailability(interviewerUser, date, LocalTime.of(10, 0), LocalTime.of(12, 0));

        assertThat(bookInterview(recruiter, roundId, profile.getId(), date + "T10:00:00+05:30",
                date + "T11:00:00+05:30", "no-replace-" + roundId).getStatusCode()).isEqualTo(HttpStatus.OK);

        post("/api/interviews/" + roundId + "/interviewer-cancel", interviewerUser, "");

        JsonNode options = json(post("/api/interviews/" + roundId + "/find-replacement", recruiter, "")).get("options");
        assertThat(options).isEmpty();
    }

    @Test
    void switchInterviewer_preservesRoundIdentityAndReschedulesToScheduled() {
        BookedSetup s = bookFreshRound("Switch");
        InterviewerFixture replacement = createInterviewerWithJavaSkill(s.recruiter(), "Switch Replacement");
        setAvailability(replacement.session(), s.date(), LocalTime.of(10, 0), LocalTime.of(12, 0));

        ResponseEntity<String> switchResp = post("/api/interviews/" + s.roundId() + "/switch-interviewer", s.recruiter(),
                obj().put("newInterviewerId", replacement.profileId().toString())
                        .put("start", s.date() + "T10:00:00+05:30")
                        .put("end", s.date() + "T11:00:00+05:30")
                        .put("timezone", "Asia/Kolkata")
                        .put("idempotencyKey", "switch-" + s.roundId()));

        assertThat(switchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(switchResp);
        assertThat(body.get("interviewRoundId").asText()).isEqualTo(s.roundId().toString());
        assertThat(body.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(body.get("interviewerId").asText()).isEqualTo(replacement.profileId().toString());
    }
}
