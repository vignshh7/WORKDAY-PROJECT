package com.interviewscheduler.scheduling;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Orchestrates find -> rank -> split into recommendation + alternatives (spec step 20). */
@Service
@RequiredArgsConstructor
public class SchedulingService {

    private static final int PRIMARY_SLOT_COUNT = 3;

    private final SlotFinderService slotFinderService;
    private final SlotRankingService slotRankingService;

    @Transactional(readOnly = true)
    public SchedulingResponse recommend(SchedulingRequest request) {
        SchedulingResponse found = slotFinderService.findSlots(request);
        if (found.slots().isEmpty()) {
            return found;
        }

        List<SlotResponse> ranked = slotRankingService.rank(request, found.slots());
        List<SlotResponse> primary = ranked.stream().limit(PRIMARY_SLOT_COUNT).toList();
        List<SlotResponse> alternatives = ranked.size() > PRIMARY_SLOT_COUNT
                ? ranked.subList(PRIMARY_SLOT_COUNT, ranked.size())
                : List.of();

        return SchedulingResponse.of(primary, alternatives);
    }
}
