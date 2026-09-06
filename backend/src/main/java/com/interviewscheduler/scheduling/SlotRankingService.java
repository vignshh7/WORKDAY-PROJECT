package com.interviewscheduler.scheduling;

import com.interviewscheduler.interviewer.InterviewerMatchResult;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure ranking (spec step 19): scores slots {@link SlotFinderService} (or any other source)
 * already determined to be structurally valid. Never re-checks feasibility.
 */
@Service
@RequiredArgsConstructor
public class SlotRankingService {

    private static final double PREFERRED_INTERVIEWER_BONUS = 10;
    private static final double PREFERRED_TIME_BONUS = 5;
    private static final double SOONER_PENALTY_PER_DAY = 0.5;

    private final InterviewerMatchingService interviewerMatchingService;

    @Transactional(readOnly = true)
    public List<SlotResponse> rank(SchedulingRequest context, List<SlotResponse> slots) {
        Map<UUID, InterviewerMatchResult> byInterviewer = interviewerMatchingService.match(context.roundId())
                .eligibleInterviewers().stream()
                .collect(Collectors.toMap(InterviewerMatchResult::interviewerId, Function.identity()));

        OffsetDateTime now = OffsetDateTime.now();

        return slots.stream()
                .map(slot -> score(slot, context, byInterviewer.get(slot.interviewerId()), now))
                .sorted(Comparator.comparingDouble(SlotResponse::score).reversed())
                .toList();
    }

    private SlotResponse score(SlotResponse slot, SchedulingRequest context,
                                InterviewerMatchResult interviewerMatch, OffsetDateTime now) {
        double score = interviewerMatch != null ? interviewerMatch.score() : 0;
        StringBuilder reason = new StringBuilder(interviewerMatch != null
                ? "Skill/domain match score " + interviewerMatch.score()
                : "No matching score available");

        if (context.preferredInterviewerId() != null
                && context.preferredInterviewerId().equals(slot.interviewerId())) {
            score += PREFERRED_INTERVIEWER_BONUS;
            reason.append("; preferred interviewer");
        }

        if (context.preferredTimeStart() != null) {
            LocalTime slotStart = slot.start().toLocalTime();
            LocalTime slotEnd = slot.end().toLocalTime();
            boolean withinPreferred = !slotStart.isBefore(context.preferredTimeStart())
                    && (context.preferredTimeEnd() == null || !slotEnd.isAfter(context.preferredTimeEnd()));
            if (withinPreferred) {
                score += PREFERRED_TIME_BONUS;
                reason.append("; within preferred time window");
            }
        }

        double daysFromNow = Math.max(0, Duration.between(now, slot.start()).toHours() / 24.0);
        score -= daysFromNow * SOONER_PENALTY_PER_DAY;
        reason.append(String.format("; %.1f day(s) out", daysFromNow));

        return new SlotResponse(slot.interviewerId(), slot.interviewerName(), slot.start(), slot.end(),
                slot.timezone(), score, reason.toString());
    }
}
