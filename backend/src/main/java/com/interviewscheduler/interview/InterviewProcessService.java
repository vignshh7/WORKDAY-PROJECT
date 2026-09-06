package com.interviewscheduler.interview;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.candidate.CandidateStatus;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.DuplicateResourceException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.InvalidStateTransitionException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarSyncService;
import com.interviewscheduler.job.Job;
import com.interviewscheduler.job.JobRepository;
import com.interviewscheduler.job.JobStatus;
import com.interviewscheduler.notification.Notification;
import com.interviewscheduler.notification.NotificationChannel;
import com.interviewscheduler.notification.NotificationRepository;
import com.interviewscheduler.notification.NotificationStatus;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InterviewProcessService {

    private static final List<RoundType> DEFAULT_ROUND_ORDER =
            List.of(RoundType.SCREENING, RoundType.TECHNICAL, RoundType.MANAGERIAL, RoundType.HR);

    private final InterviewProcessRepository interviewProcessRepository;
    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final CandidateRepository candidateRepository;
    private final JobRepository jobRepository;
    private final CalendarSyncService calendarSyncService;
    private final NotificationRepository notificationRepository;
    private final AuditService auditService;

    /**
     * Starts a candidate's pipeline for a job: one ACTIVE process plus its four rounds
     * (SCREENING -> TECHNICAL -> MANAGERIAL -> HR), each depending on the previous one.
     * A candidate can only have one ACTIVE process at a time (mirrors
     * {@link InterviewProcessRepository#findByCandidateIdAndStatus} returning a single
     * Optional, not a List).
     */
    @Transactional
    public InterviewProcessResponse create(CreateInterviewProcessRequest request) {
        Candidate candidate = candidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + request.candidateId()));
        Job job = jobRepository.findById(request.jobId())
                .orElseThrow(() -> new ResourceNotFoundException("No job with id: " + request.jobId()));
        if (job.getStatus() == JobStatus.CLOSED) {
            throw new ConflictException("Cannot start a process for a closed job");
        }

        if (candidate.getCurrentStatus() == CandidateStatus.REJECTED
                || candidate.getCurrentStatus() == CandidateStatus.WITHDRAWN) {
            throw new ConflictException(
                    "Cannot start an interview process for a rejected or withdrawn candidate");
        }
        if (interviewProcessRepository.findByCandidateIdAndStatus(candidate.getId(), ProcessStatus.ACTIVE).isPresent()) {
            throw new DuplicateResourceException("Candidate already has an active interview process");
        }

        InterviewProcess process = new InterviewProcess();
        process.setCandidate(candidate);
        process.setJob(job);
        InterviewProcess savedProcess = interviewProcessRepository.saveAndFlush(process);

        InterviewRound previous = null;
        InterviewRound firstRound = null;
        for (int i = 0; i < DEFAULT_ROUND_ORDER.size(); i++) {
            InterviewRound round = new InterviewRound();
            round.setProcess(savedProcess);
            round.setRoundNumber((short) (i + 1));
            round.setRoundType(DEFAULT_ROUND_ORDER.get(i));
            round.setDependsOnRound(previous);
            previous = interviewRoundRepository.saveAndFlush(round);
            if (i == 0) {
                firstRound = previous;
            }
        }

        candidate.setCurrentStatus(CandidateStatus.SCREENING);
        candidate.setCurrentRound(firstRound);
        candidateRepository.saveAndFlush(candidate);

        auditService.logForCurrentUser(AuditAction.INTERVIEW_PROCESS_CREATED, "INTERVIEW_PROCESS",
                savedProcess.getId(), Map.of("candidateId", candidate.getId(), "jobId", job.getId()));

        return InterviewProcessResponse.from(savedProcess);
    }

    @Transactional(readOnly = true)
    public InterviewProcessResponse findById(UUID id) {
        return InterviewProcessResponse.from(getOwnedOrStaff(id));
    }

    @Transactional(readOnly = true)
    public InterviewProcessResponse findActiveByCandidate(UUID candidateId) {
        requireCandidateAccess(candidateId);
        InterviewProcess process = interviewProcessRepository
                .findByCandidateIdAndStatus(candidateId, ProcessStatus.ACTIVE)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No active interview process for candidate: " + candidateId));
        return InterviewProcessResponse.from(process);
    }

    /** Marks the candidate's current round as COMPLETED (the interview took place, no result yet). */
    @Transactional
    public InterviewRoundResponse completeRound(UUID roundId) {
        InterviewRound round = getRoundOrThrow(roundId);
        InterviewProcess process = round.getProcess();

        if (round.getStatus() != RoundStatus.SCHEDULED && round.getStatus() != RoundStatus.IN_PROGRESS) {
            throw new InvalidStateTransitionException(
                    "Round must be SCHEDULED or IN_PROGRESS to complete; current status: " + round.getStatus());
        }
        if (!round.getRoundNumber().equals(process.getCurrentRound())) {
            throw new InvalidStateTransitionException(
                    "Cannot complete a round that is not the candidate's current round");
        }

        round.setStatus(RoundStatus.COMPLETED);
        InterviewRound saved = interviewRoundRepository.saveAndFlush(round);

        auditService.logForCurrentUser(AuditAction.ROUND_COMPLETED, "INTERVIEW_ROUND", saved.getId(), null);
        return InterviewRoundResponse.from(saved);
    }

    /**
     * Records PASS/FAIL/HOLD on an already-COMPLETED round and drives pipeline progression:
     * PASS advances to the next round (or SELECTED on the last one), FAIL rejects the
     * candidate and invalidates every future round, HOLD leaves the candidate at the same
     * stage with no progression.
     */
    @Transactional
    public InterviewRoundResponse submitResult(UUID roundId, RoundResultRequest request) {
        InterviewRound round = getRoundOrThrow(roundId);
        InterviewProcess process = round.getProcess();

        if (round.getStatus() == RoundStatus.CANCELLED) {
            throw new InvalidStateTransitionException("Cannot record a result on a cancelled round");
        }
        if (round.getStatus() != RoundStatus.COMPLETED) {
            throw new InvalidStateTransitionException("Round must be completed before a result can be recorded");
        }
        if (round.getResult() != RoundResult.PENDING) {
            throw new InvalidStateTransitionException("Result already recorded for this round");
        }

        round.setResult(request.result());
        interviewRoundRepository.saveAndFlush(round);

        Candidate candidate = process.getCandidate();
        switch (request.result()) {
            case PASS -> advanceOrComplete(process, candidate, round);
            case FAIL -> rejectAndInvalidateFuture(process, candidate, round);
            case HOLD -> { /* no progression; candidate stays at the same stage */ }
            case PENDING -> throw new InvalidStateTransitionException("PENDING is not a valid result to submit");
        }

        auditService.logForCurrentUser(AuditAction.ROUND_RESULT_UPDATED, "INTERVIEW_ROUND", round.getId(),
                Map.of("result", request.result().name()));

        return InterviewRoundResponse.from(round);
    }

    private void advanceOrComplete(InterviewProcess process, Candidate candidate, InterviewRound completedRound) {
        interviewRoundRepository.findByProcessIdAndRoundNumber(process.getId(),
                        (short) (completedRound.getRoundNumber() + 1))
                .ifPresentOrElse(
                        nextRound -> {
                            process.setCurrentRound(nextRound.getRoundNumber());
                            candidate.setCurrentStatus(toCandidateStatus(nextRound.getRoundType()));
                            candidate.setCurrentRound(nextRound);
                            interviewProcessRepository.saveAndFlush(process);
                            candidateRepository.saveAndFlush(candidate);
                        },
                        () -> {
                            process.setStatus(ProcessStatus.COMPLETED);
                            candidate.setCurrentStatus(CandidateStatus.SELECTED);
                            interviewProcessRepository.saveAndFlush(process);
                            candidateRepository.saveAndFlush(candidate);
                        });
    }

    private void rejectAndInvalidateFuture(InterviewProcess process, Candidate candidate, InterviewRound failedRound) {
        process.setStatus(ProcessStatus.REJECTED);
        candidate.setCurrentStatus(CandidateStatus.REJECTED);
        interviewProcessRepository.saveAndFlush(process);
        candidateRepository.saveAndFlush(candidate);

        List<InterviewRound> futureRounds = interviewRoundRepository
                .findByProcessIdOrderByRoundNumberAsc(process.getId()).stream()
                .filter(r -> r.getRoundNumber() > failedRound.getRoundNumber())
                .filter(r -> r.getStatus() != RoundStatus.COMPLETED && r.getStatus() != RoundStatus.CANCELLED)
                .toList();

        for (InterviewRound round : futureRounds) {
            cancelRoundCascade(round, NotificationType.INTERVIEW_CANCELLED);
        }
    }

    /** Cancels a round's calendar events, removes participants, notifies, and marks the round CANCELLED. */
    void cancelRoundCascade(InterviewRound round, NotificationType notificationType) {
        calendarSyncService.cancelAll(round.getId());
        List<InterviewParticipant> participants = interviewParticipantRepository.findByInterviewRoundId(round.getId());
        for (InterviewParticipant p : participants) {
            if (p.getStatus() != ParticipantStatus.REMOVED) {
                p.setStatus(ParticipantStatus.REMOVED);
                interviewParticipantRepository.save(p);
            }
        }
        participants.stream().map(InterviewParticipant::getUser).distinct().forEach(user -> {
            Notification n = new Notification();
            n.setUser(user); n.setInterviewRound(round);
            n.setType(notificationType); n.setChannel(NotificationChannel.IN_APP);
            n.setStatus(NotificationStatus.PENDING);
            notificationRepository.save(n);
        });
        round.setScheduledStart(null);
        round.setScheduledEnd(null);
        round.setStatus(RoundStatus.CANCELLED);
        interviewRoundRepository.save(round);
        auditService.logForCurrentUser(AuditAction.INTERVIEW_CANCELLED, "INTERVIEW_ROUND", round.getId(),
                Map.of("reason", "pipeline-invalidation"));
    }

    private static CandidateStatus toCandidateStatus(RoundType roundType) {
        return switch (roundType) {
            case SCREENING -> CandidateStatus.SCREENING;
            case TECHNICAL -> CandidateStatus.TECHNICAL;
            case MANAGERIAL -> CandidateStatus.MANAGERIAL;
            case HR -> CandidateStatus.HR;
        };
    }

    private InterviewRound getRoundOrThrow(UUID id) {
        return interviewRoundRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + id));
    }

    private InterviewProcess getOwnedOrStaff(UUID processId) {
        InterviewProcess process = interviewProcessRepository.findById(processId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview process with id: " + processId));
        assertAccess(process.getCandidate());
        return process;
    }

    private void requireCandidateAccess(UUID candidateId) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + candidateId));
        assertAccess(candidate);
    }

    /** RECRUITER/ADMIN can access any candidate's process; the candidate can access only their own. */
    private void assertAccess(Candidate candidate) {
        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = candidate.getUser().getId().equals(caller.getId());
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot access another candidate's interview process");
        }
    }
}
