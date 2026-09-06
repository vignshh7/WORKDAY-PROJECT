package com.interviewscheduler.job;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.create(request));
    }

    @GetMapping
    public List<JobResponse> getAll() {
        return jobService.findAll();
    }

    @GetMapping("/{id}")
    public JobResponse getById(@PathVariable UUID id) {
        return jobService.findById(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public JobResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateJobRequest request) {
        return jobService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        jobService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Phase 18: explicitly close a job. Sets status CLOSED without touching existing
     * interview data — active processes and scheduled interviews continue unchanged.
     * New processes cannot be started for a CLOSED job.
     */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public JobResponse close(@PathVariable UUID id) {
        return jobService.close(id);
    }

    @GetMapping("/{id}/skills")
    public List<JobSkillResponse> getSkills(@PathVariable UUID id) {
        return jobService.getSkills(id);
    }

    @PostMapping("/{id}/skills")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<JobSkillResponse> addSkill(
            @PathVariable UUID id, @Valid @RequestBody JobSkillRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.addSkill(id, request));
    }
}
