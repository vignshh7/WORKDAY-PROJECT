package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 — INTERVIEWER: skill, proficiency, domain, inactive interviewer. */
class InterviewerMatchingIntegrationTest extends AbstractIntegrationTest {

    private Session admin;
    private Session recruiter;

    private void ensureUsers() {
        if (admin == null) {
            admin = registerAndLogin("Matching Admin", "ADMIN");
        }
        if (recruiter == null) {
            recruiter = registerAndLogin("Matching Recruiter", "RECRUITER");
        }
    }

    @Test
    void interviewerWithMatchingSkillAndProficiency_isEligible() {
        ensureUsers();
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Match Eligible Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Match Eligible Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture eligible = createInterviewerWithJavaSkill(recruiter, "Match Eligible Interviewer");

        ResponseEntity<String> resp = post("/api/interviewers/match", recruiter, obj().put("roundId", roundId.toString()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode eligibleList = json(resp).get("eligibleInterviewers");
        assertThat(anyMatches(eligibleList, "interviewerId", eligible.profileId().toString())).isTrue();
    }

    @Test
    void interviewerWithInsufficientProficiency_isIneligibleWithReason() {
        ensureUsers();
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Match LowProf Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Match LowProf Role"); // requires minimumProficiency 3
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        Session lowProfInterviewerUser = registerAndLogin("Match LowProf Interviewer", "INTERVIEWER");
        var profile = new com.interviewscheduler.interviewer.InterviewerProfile();
        profile.setUser(userRepository.getReferenceById(lowProfInterviewerUser.userId()));
        profile.setDepartment("Engineering");
        profile.setDesignation("Junior Engineer");
        profile.setDomain("Backend");
        profile.setMaxInterviewsPerDay((short) 4);
        profile = interviewerProfileRepository.saveAndFlush(profile);
        ResponseEntity<String> skillResp = post("/api/interviewers/" + profile.getId() + "/skills", recruiter,
                obj().put("skillId", JAVA_SKILL_ID).put("proficiency", 1).put("yearsExperience", 1).put("isPrimary", false));
        assertThat(skillResp.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<String> resp = post("/api/interviewers/match", recruiter, obj().put("roundId", roundId.toString()));
        JsonNode ineligibleList = json(resp).get("ineligibleInterviewers");
        JsonNode match = findMatch(ineligibleList, "interviewerId", profile.getId().toString());
        assertThat(match).isNotNull();
        assertThat(match.get("failureReasons").toString()).containsIgnoringCase("proficiency");
    }

    @Test
    void interviewerWithNoRelevantSkillAtAll_isIneligible() {
        ensureUsers();
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Match NoSkill Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Match NoSkill Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);

        Session noSkillUser = registerAndLogin("Match NoSkill Interviewer", "INTERVIEWER");
        var profile = new com.interviewscheduler.interviewer.InterviewerProfile();
        profile.setUser(userRepository.getReferenceById(noSkillUser.userId()));
        profile.setDepartment("Engineering");
        profile.setDesignation("Engineer");
        profile.setDomain("Backend");
        profile.setMaxInterviewsPerDay((short) 4);
        profile = interviewerProfileRepository.saveAndFlush(profile);

        ResponseEntity<String> resp = post("/api/interviewers/match", recruiter, obj().put("roundId", roundId.toString()));
        JsonNode ineligibleList = json(resp).get("ineligibleInterviewers");
        assertThat(findMatch(ineligibleList, "interviewerId", profile.getId().toString())).isNotNull();
    }

    @Test
    void inactiveInterviewer_isIneligible() {
        ensureUsers();
        UUID candidateId = createCandidateWithJavaSkill(recruiter, "Match Inactive Candidate");
        UUID jobId = createOpenJavaJob(recruiter, "Match Inactive Role");
        UUID roundId = createProcessAndGetCurrentRoundId(recruiter, candidateId, jobId);
        InterviewerFixture interviewer = createInterviewerWithJavaSkill(recruiter, "Match Inactive Interviewer");

        ResponseEntity<String> suspendResp = exchange(org.springframework.http.HttpMethod.PATCH,
                "/api/users/" + interviewer.session().userId() + "/status", admin, obj().put("status", "SUSPENDED"));
        assertThat(suspendResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> resp = post("/api/interviewers/match", recruiter, obj().put("roundId", roundId.toString()));
        JsonNode ineligibleList = json(resp).get("ineligibleInterviewers");
        assertThat(findMatch(ineligibleList, "interviewerId", interviewer.profileId().toString())).isNotNull();
    }

    private boolean anyMatches(JsonNode array, String field, String value) {
        return findMatch(array, field, value) != null;
    }

    private JsonNode findMatch(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.get(field).asText().equals(value)) {
                return node;
            }
        }
        return null;
    }
}
