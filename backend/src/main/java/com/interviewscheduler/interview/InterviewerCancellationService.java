package com.interviewscheduler.interview;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarSyncService;
import com.interviewscheduler.notification.NotificationService;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.scheduling.SchedulingRequest;
import com.interviewscheduler.scheduling.SchedulingResponse;
import com.interviewscheduler.scheduling.SchedulingService;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 15 — Interviewer Cancellation.
 *
 * An assigned interviewer cancels their participation in a SCHEDULED round. The round is
 * immediately moved to RESCHEDULE_REQUIRED (candidate stage unchanged) and the reusable
 * scheduling engine runs to find a qualified replacement and new slots.
 */
@Service
@RequiredArgsConstructor
public class InterviewerCancellationService {

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final CalendarSyncService calendarSyncService;
    private final NotificationService notificationService;
    private final ReminderService reminderService;
    private final WorkingHoursService workingHoursService;
    private final SchedulingService schedulingService;
    private final AuditService auditService;

    /**
     * Called by the authenticated INTERVIEWER. Verifies assignment, records the cancellation,
     * transitions the round to RESCHEDULE_REQUIRED, then runs the scheduling engine to surface
     * replacement options. Returns the same SchedulingResponse the recruiter will use to pick
     * and confirm a new slot via the normal booking flow.
     */
    @Transactional
    public SchedulingResponse cancelByInterviewer(UUID roundId) {
        InterviewRound round = interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));

        UserPrincipal caller = SecurityUtils.currentUser();

        // Rule 1: only an INTERVIEWER may use this endpoint
        if (caller.getRole() != Role.INTERVIEWER) {
            throw new ForbiddenException("Only users with role INTERVIEWER may cancel via this endpoint");
        }

        // Rule 2: caller must be an active INTERVIEWER participant on this round
        InterviewParticipant participant = interviewParticipantRepository
                .findByInterviewRoundIdAndUserId(roundId, caller.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "You are not assigned as a participant on this interview"));

        if (participant.getParticipantRole() != ParticipantRole.INTERVIEWER) {
            throw new ForbiddenException("You are not assigned as an interviewer for this interview");
        }
        if (participant.getStatus() == ParticipantStatus.DECLINED
                || participant.getStatus() == ParticipantStatus.REMOVED) {
            throw new ConflictException(
                    "Your assignment to this interview is already " + participant.getStatus());
        }

        // Rule 3: interview must not have started
        if (round.getStatus() == RoundStatus.IN_PROGRESS
                || round.getStatus() == RoundStatus.COMPLETED
                || round.getStatus() == RoundStatus.CANCELLED) {
            throw new ConflictException("Cannot cancel an interview in status: " + round.getStatus());
        }
        if (round.getStatus() != RoundStatus.SCHEDULED) {
            throw new ConflictException(
                    "Interviewer cancellation requires the round to be SCHEDULED, current status: "
                            + round.getStatus());
        }

        SchedulingConfig config = workingHoursService.currentConfig();
        if (round.getRescheduleCount() >= config.getMaximumReschedules()) {
            throw new ConflictException("Maximum number of reschedules ("
                    + config.getMaximumReschedules() + ") has already been reached for this round");
        }

        // Rule 4: record cancellation
        participant.setStatus(ParticipantStatus.DECLINED);
        interviewParticipantRepository.save(participant);

        // Rule 7: cancel existing calendar events
        calendarSyncService.cancelAll(round.getId());
        reminderService.invalidateReminders(round.getId());

        // Rule 5 & 6: round -> RESCHEDULE_REQUIRED, candidate stage unchanged
        round.setScheduledStart(null);
        round.setScheduledEnd(null);
        round.setStatus(RoundStatus.RESCHEDULE_REQUIRED);
        round.setRescheduleCount((short) (round.getRescheduleCount() + 1));
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        // Notify all active participants
        notifyParticipants(savedRound, NotificationType.INTERVIEWER_CANCELLED);

        // Audit: INTERVIEWER_CANCELLED
        auditService.logForCurrentUser(AuditAction.INTERVIEWER_CANCELLED, "INTERVIEW_ROUND",
                savedRound.getId(), Map.of("cancelledByUserId", caller.getId().toString()));

        // Rule 8-13: trigger rescheduling engine — no preferred interviewer so a qualified
        // replacement is found rather than re-offering the same (now declined) interviewer
        Candidate candidate = savedRound.getProcess().getCandidate();
        List<UUID> otherParticipantIds = otherRequiredParticipantIds(savedRound);
        String timezone = savedRound.getTimezone() != null
                ? savedRound.getTimezone() : candidate.getUser().getTimezone();

        LocalDate dateFrom = LocalDate.now();
        LocalDate dateTo = dateFrom.plusDays(config.getMaximumSchedulingDays());

        SchedulingRequest searchRequest = new SchedulingRequest(
                candidate.getId(),
                savedRound.getId(),
                null,   // no preferred interviewer — find any qualified replacement
                savedRound.getDurationMinutes(),
                dateFrom, dateTo,
                null, null, null,
                otherParticipantIds,
                timezone);

        SchedulingResponse response = schedulingService.recommend(searchRequest);

        if (!response.slots().isEmpty()) {
            auditService.logForCurrentUser(AuditAction.RESCHEDULING_SLOT_FOUND, "INTERVIEW_ROUND",
                    savedRound.getId(),
                    Map.of("slotsFound", response.slots().size(),
                            "alternativesFound", response.alternatives().size()));
        }

        return response;
    }

    private void notifyParticipants(InterviewRound round, NotificationType type) {
        interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED)
                .map(InterviewParticipant::getUser)
                .distinct()
                .forEach(user -> notificationService.notify(user, round, type));
    }

    private List<UUID> otherRequiredParticipantIds(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED
                        && p.getStatus() != ParticipantStatus.DECLINED)
                .filter(p -> p.getParticipantRole() != ParticipantRole.CANDIDATE
                        && p.getParticipantRole() != ParticipantRole.INTERVIEWER)
                .map(p -> p.getUser().getId())
                .distinct()
                .toList();
    }
}
