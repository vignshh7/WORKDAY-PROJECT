package com.interviewscheduler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.common.Skill;
import com.interviewscheduler.common.SkillRepository;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import com.interviewscheduler.notification.NotificationRepository;
import com.interviewscheduler.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Phase 28 — shared base for the live integration test suite. Runs as a plain
 * {@code @SpringBootTest} against whatever database {@code DATABASE_URL}/{@code DB_USERNAME}/
 * {@code DB_PASSWORD} point at (the real Supabase instance used throughout every prior phase's
 * live verification, not Testcontainers/Docker) - unlike {@link InterviewSchedulerApplicationTests},
 * these tests need no Docker and run with a plain {@code ./mvnw test -Dtest=...}.
 *
 * <p>Every request goes over real HTTP to a real running instance of this application
 * ({@code TestRestTemplate} against {@code webEnvironment = RANDOM_PORT}), exercising the same
 * Spring Security filter chain, validation, and transaction/locking behavior a production
 * request would - not a mocked slice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final String PASSWORD = "Password123!";
    protected static final String JAVA_SKILL_ID = "424ade34-7ff0-43dd-9e8c-67f8c1fff53d";

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected InterviewerProfileRepository interviewerProfileRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected WorkingHoursService workingHoursService;

    @Autowired
    protected CalendarEventRepository calendarEventRepository;

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected SkillRepository skillRepository;

    /** A brand-new, uniquely-named skill nobody else has - for tests that need to guarantee
     *  "no other interviewer is eligible" without depending on the shared database's history. */
    protected UUID createUniqueSkill(String namePrefix) {
        Skill skill = new Skill();
        skill.setName(namePrefix + "-" + UUID.randomUUID());
        skill.setDomain("Backend");
        return skillRepository.saveAndFlush(skill).getId();
    }

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }

    protected String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@phase28.test";
    }

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    protected record Session(String token, UUID userId, String email) {
        HttpHeaders authHeaders() {
            HttpHeaders h = new HttpHeaders();
            h.setBearerAuth(token);
            h.setContentType(MediaType.APPLICATION_JSON);
            return h;
        }
    }

    protected ResponseEntity<String> register(String name, String email, String password, String role) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name).put("email", email).put("password", password)
                .put("role", role).put("timezone", "Asia/Kolkata");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url("/api/auth/register"), new HttpEntity<>(body.toString(), headers), String.class);
    }

    protected ResponseEntity<String> login(String email, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", email).put("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity(url("/api/auth/login"), new HttpEntity<>(body.toString(), headers), String.class);
    }

    protected Session registerAndLogin(String name, String role) {
        String email = uniqueEmail(role.toLowerCase());
        ResponseEntity<String> registerResp = register(name, email, PASSWORD, role);
        if (!registerResp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("register failed: " + registerResp.getBody());
        }
        UUID userId = UUID.fromString(json(registerResp).get("id").asText());
        ResponseEntity<String> loginResp = login(email, PASSWORD);
        if (!loginResp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("login failed: " + loginResp.getBody());
        }
        String token = json(loginResp).get("token").asText();
        return new Session(token, userId, email);
    }

    // -------------------------------------------------------------------------
    // Generic HTTP helpers
    // -------------------------------------------------------------------------

    protected ResponseEntity<String> post(String path, Session session, Object bodyAsJsonNode) {
        return exchange(HttpMethod.POST, path, session, bodyAsJsonNode);
    }

    protected ResponseEntity<String> put(String path, Session session, Object bodyAsJsonNode) {
        return exchange(HttpMethod.PUT, path, session, bodyAsJsonNode);
    }

    protected ResponseEntity<String> get(String path, Session session) {
        return exchange(HttpMethod.GET, path, session, null);
    }

    protected ResponseEntity<String> exchange(HttpMethod method, String path, Session session, Object body) {
        HttpHeaders headers = session == null ? new HttpHeaders() : session.authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = body == null ? null : (body instanceof String s ? s : body.toString());
        return restTemplate.exchange(url(path), method, new HttpEntity<>(json, headers), String.class);
    }

    protected JsonNode json(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Not valid JSON: " + response.getBody(), e);
        }
    }

    protected ObjectNode obj() {
        return objectMapper.createObjectNode();
    }

    // -------------------------------------------------------------------------
    // Common domain setup - candidate / job / process / availability
    // -------------------------------------------------------------------------

    /** Registers a CANDIDATE user, creates their profile with a Java skill, and returns the candidate id. */
    protected UUID createCandidateWithJavaSkill(Session staff, String namePrefix) {
        return createCandidateWithSkill(staff, namePrefix, UUID.fromString(JAVA_SKILL_ID));
    }

    protected UUID createCandidateWithSkill(Session staff, String namePrefix, UUID skillId) {
        Session candidateUser = registerAndLogin(namePrefix, "CANDIDATE");
        ObjectNode body = obj().put("userId", candidateUser.userId().toString())
                .put("phone", "9" + String.valueOf(System.nanoTime()).substring(0, 9))
                .put("resumeUrl", "http://example.com/resume.pdf");
        ResponseEntity<String> resp = post("/api/candidates", staff, body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("create candidate failed: " + resp.getBody());
        }
        UUID candidateId = UUID.fromString(json(resp).get("id").asText());

        ObjectNode skillBody = obj().put("skillId", skillId.toString()).put("proficiency", 4).put("yearsExperience", 3);
        ResponseEntity<String> skillResp = post("/api/candidates/" + candidateId + "/skills", staff, skillBody);
        if (!skillResp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("add candidate skill failed: " + skillResp.getBody());
        }
        return candidateId;
    }

    protected UUID createOpenJavaJob(Session staff, String title) {
        return createOpenJobRequiringSkill(staff, title, UUID.fromString(JAVA_SKILL_ID));
    }

    protected UUID createOpenJobRequiringSkill(Session staff, String title, UUID skillId) {
        ObjectNode body = obj().put("title", title).put("description", "Java role")
                .put("department", "Engineering").put("domain", "Backend");
        ResponseEntity<String> resp = post("/api/jobs", staff, body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("create job failed: " + resp.getBody());
        }
        UUID jobId = UUID.fromString(json(resp).get("id").asText());

        ObjectNode skillBody = obj().put("skillId", skillId.toString()).put("required", true)
                .put("weight", 1.0).put("minimumProficiency", 3);
        post("/api/jobs/" + jobId + "/skills", staff, skillBody);
        return jobId;
    }

    /** Creates the process and returns the candidate's current (SCREENING) round id. */
    protected UUID createProcessAndGetCurrentRoundId(Session staff, UUID candidateId, UUID jobId) {
        ObjectNode body = obj().put("candidateId", candidateId.toString()).put("jobId", jobId.toString());
        ResponseEntity<String> resp = post("/api/interview-processes", staff, body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("create process failed: " + resp.getBody());
        }
        ResponseEntity<String> candidateResp = get("/api/candidates/" + candidateId, staff);
        return UUID.fromString(json(candidateResp).get("currentRoundId").asText());
    }

    protected record InterviewerFixture(UUID profileId, Session session) {
    }

    /**
     * Registers an INTERVIEWER user and seeds their {@code interviewer_profiles} row directly
     * via JPA - there is no {@code POST /api/interviewers} endpoint (profiles are provisioned
     * outside the API by design, per Phase 8), matching how every prior phase's live testing
     * seeded one via direct SQL.
     */
    protected InterviewerFixture createInterviewerWithJavaSkill(Session staff, String namePrefix) {
        Session interviewerUser = registerAndLogin(namePrefix, "INTERVIEWER");
        InterviewerProfile profile = new InterviewerProfile();
        profile.setUser(userRepository.getReferenceById(interviewerUser.userId()));
        profile.setDepartment("Engineering");
        profile.setDesignation("Senior Engineer");
        profile.setDomain("Backend");
        profile.setMaxInterviewsPerDay((short) 4);
        profile = interviewerProfileRepository.saveAndFlush(profile);

        ObjectNode skillBody = obj().put("skillId", JAVA_SKILL_ID).put("proficiency", 4)
                .put("yearsExperience", 3).put("isPrimary", true);
        ResponseEntity<String> skillResp = post("/api/interviewers/" + profile.getId() + "/skills", staff, skillBody);
        if (!skillResp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("add interviewer skill failed: " + skillResp.getBody());
        }
        return new InterviewerFixture(profile.getId(), interviewerUser);
    }

    protected void setAvailability(Session user, LocalDate date, LocalTime start, LocalTime end) {
        ObjectNode body = obj().put("date", date.toString())
                .put("startTime", start.toString()).put("endTime", end.toString())
                .put("status", "AVAILABLE").put("timezone", "Asia/Kolkata");
        ResponseEntity<String> resp = post("/api/availability", user, body);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("set availability failed: " + resp.getBody());
        }
    }

    /** A weekday at least {@code minDaysOut} days out - clear of both the notice-period floor
     *  and the default "no weekends" scheduling-config rule. */
    protected LocalDate nextWeekday(int minDaysOut) {
        LocalDate date = LocalDate.now().plusDays(minDaysOut);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    protected ResponseEntity<String> bookInterview(Session staff, UUID roundId, UUID interviewerProfileId,
                                                     String startIso, String endIso, String idempotencyKey) {
        ObjectNode body = obj().put("interviewerId", interviewerProfileId.toString())
                .put("start", startIso).put("end", endIso)
                .put("timezone", "Asia/Kolkata").put("idempotencyKey", idempotencyKey);
        return post("/api/interviews/" + roundId + "/book", staff, body);
    }
}
