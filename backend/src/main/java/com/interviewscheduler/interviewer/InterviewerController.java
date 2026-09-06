package com.interviewscheduler.interviewer;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/interviewers")
@RequiredArgsConstructor
public class InterviewerController {

    private final InterviewerService interviewerService;
    private final InterviewerMatchingService interviewerMatchingService;

    @GetMapping
    public List<InterviewerResponse> getAll() {
        return interviewerService.findAll();
    }

    @GetMapping("/{id}")
    public InterviewerResponse getById(@PathVariable UUID id) {
        return interviewerService.findById(id);
    }

    @GetMapping("/{id}/skills")
    public List<InterviewerSkillResponse> getSkills(@PathVariable UUID id) {
        return interviewerService.getSkills(id);
    }

    @PostMapping("/{id}/skills")
    public ResponseEntity<InterviewerSkillResponse> addSkill(
            @PathVariable UUID id, @Valid @RequestBody InterviewerSkillRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(interviewerService.addSkill(id, request));
    }

    @PostMapping("/match")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public MatchInterviewersResponse match(@Valid @RequestBody MatchInterviewersRequest request) {
        return interviewerMatchingService.match(request.roundId());
    }
}
