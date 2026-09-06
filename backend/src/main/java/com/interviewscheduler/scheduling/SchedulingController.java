package com.interviewscheduler.scheduling;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scheduling")
@RequiredArgsConstructor
public class SchedulingController {

    private final ConflictDetectionService conflictDetectionService;
    private final SlotFinderService slotFinderService;
    private final SlotRankingService slotRankingService;
    private final SchedulingService schedulingService;

    @PostMapping("/check-conflicts")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public ConflictCheckResponse checkConflicts(@Valid @RequestBody CheckConflictsRequest request) {
        return conflictDetectionService.checkConflicts(request);
    }

    @PostMapping("/find-slots")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public SchedulingResponse findSlots(@Valid @RequestBody SchedulingRequest request) {
        return slotFinderService.findSlots(request);
    }

    @PostMapping("/rank-slots")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public List<SlotResponse> rankSlots(@Valid @RequestBody RankSlotsRequest request) {
        return slotRankingService.rank(request.context(), request.slots());
    }

    @PostMapping("/recommend")
    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    public SchedulingResponse recommend(@Valid @RequestBody SchedulingRequest request) {
        return schedulingService.recommend(request);
    }
}
