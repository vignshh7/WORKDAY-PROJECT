package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 — AUTH: register, login, bad password, invalid JWT, RBAC. */
class AuthRbacIntegrationTest extends AbstractIntegrationTest {

    @Test
    void registerThenLogin_succeeds() {
        String email = uniqueEmail("auth-happy");
        ResponseEntity<String> registerResp = register("Auth Happy", email, PASSWORD, "RECRUITER");
        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(json(registerResp).has("passwordHash")).isFalse();

        ResponseEntity<String> loginResp = login(email, PASSWORD);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = json(loginResp);
        assertThat(body.get("token").asText()).isNotBlank();
        assertThat(body.get("role").asText()).isEqualTo("RECRUITER");
    }

    @Test
    void register_duplicateEmail_returns409() {
        String email = uniqueEmail("auth-dup");
        assertThat(register("First", email, PASSWORD, "RECRUITER").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(register("Second", email, PASSWORD, "RECRUITER").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_wrongPassword_returns401() {
        String email = uniqueEmail("auth-badpw");
        register("Bad Password User", email, PASSWORD, "CANDIDATE");
        ResponseEntity<String> resp = login(email, "TotallyWrongPassword1!");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_unknownEmail_returns401NotSomethingThatLeaksExistence() {
        ResponseEntity<String> resp = login(uniqueEmail("does-not-exist"), PASSWORD);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_noToken_returns401() {
        ResponseEntity<String> resp = get("/api/candidates", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoint_garbageJwt_returns401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer this.is.not.a.valid.jwt");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                url("/api/candidates"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rbac_candidateCannotListAllCandidates_recruiterCan() {
        Session candidate = registerAndLogin("RBAC List Candidate", "CANDIDATE");
        Session recruiter = registerAndLogin("RBAC List Recruiter", "RECRUITER");

        assertThat(get("/api/candidates", candidate).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/candidates", recruiter).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rbac_candidateCannotReadAnotherCandidatesProfile() {
        Session recruiter = registerAndLogin("RBAC Owner Recruiter", "RECRUITER");
        var ownCandidateId = createCandidateWithJavaSkill(recruiter, "RBAC Own Candidate");

        Session otherCandidate = registerAndLogin("RBAC Other Candidate", "CANDIDATE");

        assertThat(get("/api/candidates/" + ownCandidateId, otherCandidate).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/candidates/" + ownCandidateId, recruiter).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void rbac_candidateCannotCreateJob() {
        Session candidate = registerAndLogin("RBAC Job Candidate", "CANDIDATE");
        ResponseEntity<String> resp = post("/api/jobs", candidate,
                obj().put("title", "Should not be creatable").put("department", "Eng").put("domain", "Backend"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
