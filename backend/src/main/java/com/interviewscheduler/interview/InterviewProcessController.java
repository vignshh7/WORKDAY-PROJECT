package com.interviewscheduler.interview;

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

import java.util.UUID;

@RestController
@RequestMapping("/api/interview-processes")
@RequiredArgsConstructor
public class InterviewProcessController {

    private final InterviewProcessService interviewProcessService;

    @PostMapping
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ResponseEntity<InterviewProcessResponse> create(@Valid @RequestBody CreateInterviewProcessRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(interviewProcessService.create(request));
    }

    @GetMapping("/{id}")
    public InterviewProcessResponse getById(@PathVariable UUID id) {
        return interviewProcessService.findById(id);
    }
}
