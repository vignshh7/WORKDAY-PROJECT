package com.interviewscheduler.interview;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.candidate.CandidateStatus;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 18 — Pipeline Consistency: candidate withdrawal.
 *
 * <p>Withdrawal cancels all of the candidate's non-terminal rounds (PENDING, SCHEDULED,
 * RESCHEDULE_REQUIRED), cancels their calendar events, removes participants, notifies, and
 * marks the candidate WITHDRAWN + any ACTIVE process CANCELLED. The scheduling guard
 * ({@link com.interviewscheduler.scheduling.SchedulabilityGuard}) already prevents new
 * scheduling for WITHDRAWN candidates, so no additional guard is required here.
 */
@Service
@RequiredArgsConstructor
public class PipelineConsistencyService {

    private final CandidateRepository candidateRepository;
    private final InterviewProcessRepository interviewProcessRepository;
    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewProcessService interviewProcessService;
    private final AuditService auditService;

    @Transactional
    public void withdraw(UUID candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + candidateId));

        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = candidate.getUser().getId().equals(caller.getId());
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot withdraw another candidate");
        }

        if (candidate.getCurrentStatus() == CandidateStatus.WITHDRAWN) {
            throw new ConflictException("Candidate is already WITHDRAWN");
        }
        if (candidate.getCurrentStatus() == CandidateStatus.REJECTED) {
            throw new ConflictException("Cannot withdraw a rejected candidate");
        }

        // Cancel all non-terminal rounds across all processes
        List<InterviewProcess> processes = interviewProcessRepository.findByCandidateId(candidateId);
        for (InterviewProcess process : processes) {
            if (process.getStatus() != ProcessStatus.ACTIVE) {
                continue;
            }
            List<InterviewRound> rounds = interviewRoundRepository
                    .findByProcessIdOrderByRoundNumberAsc(process.getId());
            for (InterviewRound round : rounds) {
                RoundStatus rs = round.getStatus();
                if (rs == RoundStatus.PENDING || rs == RoundStatus.SCHEDULED
                        || rs == RoundStatus.RESCHEDULE_REQUIRED) {
                    interviewProcessService.cancelRoundCascade(round, NotificationType.INTERVIEW_CANCELLED);
                }
            }
            process.setStatus(ProcessStatus.CANCELLED);
            interviewProcessRepository.save(process);
        }

        candidate.setCurrentStatus(CandidateStatus.WITHDRAWN);
        candidate.setCurrentRound(null);
        candidateRepository.save(candidate);

        auditService.logForCurrentUser(AuditAction.CANDIDATE_WITHDRAWN, "CANDIDATE", candidateId,
                Map.of("withdrawnByUserId", caller.getId().toString()));
    }
}
