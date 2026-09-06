package com.interviewscheduler.interviewer;

import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interview.InterviewParticipantRepository;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.ParticipantStatus;
import com.interviewscheduler.interview.RoundRequirement;
import com.interviewscheduler.interview.RoundRequirementRepository;
import com.interviewscheduler.job.Job;
import com.interviewscheduler.job.JobSkill;
import com.interviewscheduler.job.JobSkillRepository;
import com.interviewscheduler.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Finds interviewers eligible for a round and ranks the eligible ones.
 *
 * <p>The spec's "matching order" (job requirements -> round requirements -> hard skill
 * eligibility -> domain -> active status -> workload) and its separate "hard eligibility" /
 * "soft ranking" lists disagree on whether domain and active-status are disqualifying or
 * ranking-only: the hard-eligibility list is the more specific of the two, so it wins here —
 * {@link #isEligible} disqualifies on missing/under-proficient required skills and an
 * inactive user account; domain, primary skills, proficiency headroom and workload are
 * ranking-only, per the soft-ranking list.
 */
@Service
@RequiredArgsConstructor
public class InterviewerMatchingService {

    private final InterviewRoundRepository interviewRoundRepository;
    private final RoundRequirementRepository roundRequirementRepository;
    private final JobSkillRepository jobSkillRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final InterviewerSkillRepository interviewerSkillRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;

    @Transactional(readOnly = true)
    public MatchInterviewersResponse match(UUID roundId) {
        InterviewRound round = interviewRoundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));
        Job job = round.getProcess().getJob();

        List<RequirementSpec> requirements = effectiveRequirements(round, job);
        List<RequirementSpec> hardRequirements = requirements.stream().filter(RequirementSpec::required).toList();

        List<InterviewerMatchResult> eligible = new ArrayList<>();
        List<InterviewerMatchResult> ineligible = new ArrayList<>();

        for (InterviewerProfile interviewer : interviewerProfileRepository.findAll()) {
            Map<UUID, InterviewerSkill> ownedSkills = interviewerSkillRepository
                    .findByInterviewerId(interviewer.getId()).stream()
                    .collect(Collectors.toMap(s -> s.getSkill().getId(), Function.identity()));

            List<String> failureReasons = hardEligibilityFailures(interviewer, hardRequirements, ownedSkills);
            InterviewerMatchResult result;
            if (failureReasons.isEmpty()) {
                double score = rank(interviewer, job, requirements, ownedSkills);
                result = new InterviewerMatchResult(interviewer.getId(), interviewer.getUser().getId(),
                        interviewer.getUser().getName(), true, List.of(), score);
                eligible.add(result);
            } else {
                result = new InterviewerMatchResult(interviewer.getId(), interviewer.getUser().getId(),
                        interviewer.getUser().getName(), false, failureReasons, null);
                ineligible.add(result);
            }
        }

        eligible.sort(Comparator.comparingDouble(InterviewerMatchResult::score).reversed());
        return new MatchInterviewersResponse(round.getId(), eligible, ineligible);
    }

    /** Round-specific requirements take precedence; fall back to the job's requirements. */
    private List<RequirementSpec> effectiveRequirements(InterviewRound round, Job job) {
        List<RoundRequirement> roundRequirements = roundRequirementRepository.findByRoundId(round.getId());
        if (!roundRequirements.isEmpty()) {
            return roundRequirements.stream()
                    .map(r -> new RequirementSpec(r.getSkill().getId(), r.getSkill().getName(),
                            r.getMinimumProficiency(), r.isRequired()))
                    .toList();
        }
        List<JobSkill> jobSkills = jobSkillRepository.findByJobId(job.getId());
        return jobSkills.stream()
                .map(s -> new RequirementSpec(s.getSkill().getId(), s.getSkill().getName(),
                        s.getMinimumProficiency(), s.isRequired()))
                .toList();
    }

    private List<String> hardEligibilityFailures(InterviewerProfile interviewer,
                                                  List<RequirementSpec> hardRequirements,
                                                  Map<UUID, InterviewerSkill> ownedSkills) {
        List<String> reasons = new ArrayList<>();
        if (interviewer.getUser().getStatus() != UserStatus.ACTIVE) {
            reasons.add("Interviewer is not ACTIVE");
        }
        for (RequirementSpec requirement : hardRequirements) {
            InterviewerSkill owned = ownedSkills.get(requirement.skillId());
            if (owned == null) {
                reasons.add("Missing required skill: " + requirement.skillName());
            } else if (owned.getProficiency() < requirement.minimumProficiency()) {
                reasons.add("Proficiency in " + requirement.skillName() + " (" + owned.getProficiency()
                        + ") is below the required minimum (" + requirement.minimumProficiency() + ")");
            }
        }
        return reasons;
    }

    private double rank(InterviewerProfile interviewer, Job job, List<RequirementSpec> requirements,
                         Map<UUID, InterviewerSkill> ownedSkills) {
        double score = 0;
        for (RequirementSpec requirement : requirements) {
            InterviewerSkill owned = ownedSkills.get(requirement.skillId());
            if (owned == null) {
                continue;
            }
            score += owned.getProficiency();
            if (owned.getProficiency() > requirement.minimumProficiency()) {
                score += (owned.getProficiency() - requirement.minimumProficiency()) * 0.5;
            }
            if (owned.isPrimary()) {
                score += 2;
            }
        }
        if (job.getDomain() != null && job.getDomain().equalsIgnoreCase(interviewer.getDomain())) {
            score += 5;
        }
        score -= currentWorkload(interviewer);
        return score;
    }

    /**
     * Active (non-removed) INTERVIEWER assignments across all rounds. A coarse proxy until
     * Phase 12+ scheduling gives rounds real dates to compute per-day workload from.
     */
    private long currentWorkload(InterviewerProfile interviewer) {
        return interviewParticipantRepository
                .findByUserIdAndStatusNot(interviewer.getUser().getId(), ParticipantStatus.REMOVED).stream()
                .filter(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER)
                .count();
    }

    private record RequirementSpec(UUID skillId, String skillName, Short minimumProficiency, boolean required) {
    }
}
