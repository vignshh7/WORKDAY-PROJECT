package com.interviewscheduler.candidate;

import com.interviewscheduler.interview.InterviewProcessResponse;
import com.interviewscheduler.interview.InterviewProcessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

    private final CandidateService candidateService;
    private final InterviewProcessService interviewProcessService;

    @PostMapping
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<CandidateResponse> create(@Valid @RequestBody CreateCandidateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(candidateService.create(request));
    }

    @GetMapping
    public List<CandidateResponse> getAll() {
        return candidateService.findAll();
    }

    @GetMapping("/{id}")
    public CandidateResponse getById(@PathVariable UUID id) {
        return candidateService.findById(id);
    }

    @PutMapping("/{id}")
    public CandidateResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCandidateRequest request) {
        return candidateService.update(id, request);
    }

    @GetMapping("/{id}/skills")
    public List<CandidateSkillResponse> getSkills(@PathVariable UUID id) {
        return candidateService.getSkills(id);
    }

    @PostMapping("/{id}/skills")
    public ResponseEntity<CandidateSkillResponse> addSkill(
            @PathVariable UUID id, @Valid @RequestBody CandidateSkillRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(candidateService.addSkill(id, request));
    }

    @GetMapping("/{id}/pipeline")
    public List<CandidatePipelineEntry> getPipeline(@PathVariable UUID id) {
        return candidateService.getPipeline(id);
    }

    @GetMapping("/{id}/status")
    public CandidateStatusResponse getStatus(@PathVariable UUID id) {
        return candidateService.getStatus(id);
    }

    @GetMapping("/{id}/interview-process")
    public InterviewProcessResponse getInterviewProcess(@PathVariable UUID id) {
        return interviewProcessService.findActiveByCandidate(id);
    }
}
