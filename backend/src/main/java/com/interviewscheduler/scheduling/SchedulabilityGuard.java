package com.interviewscheduler.scheduling;

import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateStatus;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.RoundResult;
import com.interviewscheduler.interview.RoundStatus;
import org.springframework.stereotype.Component;

/**
 * Shared by {@link SlotFinderService} (before searching) and {@link InterviewBookingService}
 * (again, right before booking - a slot search and the eventual booking attempt can be
 * minutes or days apart, so this must be re-checked, not just checked once upstream).
 */
@Component
public class SchedulabilityGuard {

    public void validate(Candidate candidate, InterviewRound round) {
        if (candidate.getCurrentStatus() == CandidateStatus.REJECTED
                || candidate.getCurrentStatus() == CandidateStatus.WITHDRAWN) {
            throw new ConflictException("Cannot schedule an interview for a rejected or withdrawn candidate");
        }
        if (round.getStatus() != RoundStatus.PENDING && round.getStatus() != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException("Round is not in a schedulable state: " + round.getStatus());
        }
        InterviewRound dependsOn = round.getDependsOnRound();
        if (dependsOn != null
                && !(dependsOn.getStatus() == RoundStatus.COMPLETED && dependsOn.getResult() == RoundResult.PASS)) {
            throw new ConflictException("Round " + round.getRoundNumber() + " depends on round "
                    + dependsOn.getRoundNumber() + ", which has not been completed with a PASS result");
        }
    }
}
