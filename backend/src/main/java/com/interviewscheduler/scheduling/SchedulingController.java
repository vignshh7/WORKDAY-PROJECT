package com.interviewscheduler.scheduling;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduling")
@RequiredArgsConstructor
public class SchedulingController {

    private final ConflictDetectionService conflictDetectionService;

    @PostMapping("/check-conflicts")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ConflictCheckResponse checkConflicts(@Valid @RequestBody CheckConflictsRequest request) {
        return conflictDetectionService.checkConflicts(request);
    }
}
