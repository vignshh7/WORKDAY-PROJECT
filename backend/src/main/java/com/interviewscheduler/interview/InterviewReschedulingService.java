package com.interviewscheduler.interview;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarSyncService;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
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
 * Reusable rescheduling engine: EVENT -> RESCHEDULE_REQUIRED -> (re-run the Phase 11/12
 * find/rank pipeline) -> confirmation -> fresh validation -> booking -> SCHEDULED. Phase 14
 * wires the two generic triggers it exposes directly (a recruiter or the candidate asking to
 * move the time; a recruiter cancelling outright); Phase 15's interviewer-cancel and Phase 17's
 * participant-decline handling are expected to call {@link #reschedule} the same way instead
 * of duplicating this flow.
 */
@Service
@RequiredArgsConstructor
public class InterviewReschedulingService {

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final CalendarSyncService calendarSyncService;
    private final NotificationService notificationService;
    private final WorkingHoursService workingHoursService;
    private final SchedulingService schedulingService;
    private final AuditService auditService;
    private final ReminderService reminderService;

    /**
     * Candidate stage never changes here (unlike a PASS/FAIL result) - only the round's own
     * schedule state moves, which is exactly what makes this reusable across every trigger the
     * spec lists (interviewer cancellation, a recruiter or candidate wanting a different time,
     * a calendar conflict/sync issue, a decline): none of them should touch the pipeline stage.
     */
    @Transactional
    public SchedulingResponse reschedule(UUID roundId, RescheduleRequest request) {
        InterviewRound round = loadForUpdate(roundId);
        assertRescheduleAccess(round.getProcess().getCandidate());
        UserPrincipal caller = SecurityUtils.currentUser();
        return doReschedule(round, request, caller.getId(), ActorType.USER);
    }

    /**
     * System-initiated variant for triggers with no authenticated human caller - Phase 21's
     * calendar-webhook reconciliation is the current caller (an external drift was detected:
     * the event was deleted, its time moved, an attendee declined, or a new conflict emerged).
     * Skips {@link #assertRescheduleAccess}; otherwise runs the identical reusable flow.
     */
    @Transactional
    public SchedulingResponse rescheduleSystemInitiated(UUID roundId, String reason) {
        InterviewRound round = loadForUpdate(roundId);
        RescheduleRequest request = new RescheduleRequest(reason, null, null, null, null);
        return doReschedule(round, request, null, ActorType.SYSTEM);
    }

    private SchedulingResponse doReschedule(InterviewRound round, RescheduleRequest request,
                                             UUID actorId, ActorType actorType) {
        Candidate candidate = round.getProcess().getCandidate();

        if (round.getStatus() != RoundStatus.SCHEDULED && round.getStatus() != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException("Round is not in a reschedulable state: " + round.getStatus());
        }

        SchedulingConfig config = workingHoursService.currentConfig();
        if (round.getRescheduleCount() >= config.getMaximumReschedules()) {
            throw new ConflictException("Maximum number of reschedules (" + config.getMaximumReschedules()
                    + ") has already been reached for this round");
        }

        UUID preferredInterviewerId = currentInterviewerProfileId(round);
        List<UUID> otherParticipantIds = otherRequiredParticipantIds(round);
        String searchTimezone = round.getTimezone() != null ? round.getTimezone() : candidate.getUser().getTimezone();

        cancelExistingCalendarEvents(round);
        reminderService.invalidateReminders(round.getId());

        round.setScheduledStart(null);
        round.setScheduledEnd(null);
        round.setStatus(RoundStatus.RESCHEDULE_REQUIRED);
        round.setRescheduleCount((short) (round.getRescheduleCount() + 1));
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        notifyParticipants(savedRound, NotificationType.INTERVIEW_RESCHEDULED);
        auditService.log(actorId, actorType, AuditAction.INTERVIEW_RESCHEDULED, "INTERVIEW_ROUND", savedRound.getId(),
                Map.of("reason", request.reason() == null ? "" : request.reason()));

        LocalDate dateFrom = request.dateFrom() != null ? request.dateFrom() : LocalDate.now();
        LocalDate dateTo = request.dateTo() != null
                ? request.dateTo() : dateFrom.plusDays(config.getMaximumSchedulingDays());

        SchedulingRequest searchRequest = new SchedulingRequest(
                candidate.getId(), savedRound.getId(), preferredInterviewerId, savedRound.getDurationMinutes(),
                dateFrom, dateTo, request.preferredTimeStart(), request.preferredTimeEnd(), null,
                otherParticipantIds, searchTimezone);

        return schedulingService.recommend(searchRequest);
    }

    /** Recruiter-intentional cancellation: CANCELLED, no automatic rescheduling triggered. */
    @Transactional
    public InterviewRoundResponse cancel(UUID roundId) {
        InterviewRound round = loadForUpdate(roundId);

        if (round.getStatus() == RoundStatus.COMPLETED || round.getStatus() == RoundStatus.CANCELLED
                || round.getStatus() == RoundStatus.IN_PROGRESS) {
            throw new ConflictException("Cannot cancel a round with status: " + round.getStatus());
        }

        cancelExistingCalendarEvents(round);
        removeParticipants(round);
        reminderService.invalidateReminders(round.getId());

        round.setStatus(RoundStatus.CANCELLED);
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        notifyParticipants(savedRound, NotificationType.INTERVIEW_CANCELLED);
        auditService.logForCurrentUser(AuditAction.INTERVIEW_CANCELLED, "INTERVIEW_ROUND", savedRound.getId(), null);

        return InterviewRoundResponse.from(savedRound);
    }

    private InterviewRound loadForUpdate(UUID roundId) {
        return interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));
    }

    private void assertRescheduleAccess(Candidate candidate) {
        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = candidate.getUser().getId().equals(caller.getId());
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot reschedule another candidate's interview");
        }
    }

    private void cancelExistingCalendarEvents(InterviewRound round) {
        calendarSyncService.cancelAll(round.getId());
    }

    /** Frees the interviewer's workload count (Phase 8) - a cancelled round shouldn't count against it. */
    private void removeParticipants(InterviewRound round) {
        for (InterviewParticipant participant : interviewParticipantRepository.findByInterviewRoundId(round.getId())) {
            if (participant.getStatus() != ParticipantStatus.REMOVED) {
                participant.setStatus(ParticipantStatus.REMOVED);
                interviewParticipantRepository.save(participant);
            }
        }
    }

    private void notifyParticipants(InterviewRound round, NotificationType type) {
        interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .map(InterviewParticipant::getUser)
                .distinct()
                .forEach(user -> notificationService.notify(user, round, type));
    }

    private UUID currentInterviewerProfileId(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER
                        && p.getStatus() != ParticipantStatus.REMOVED)
                .findFirst()
                .flatMap(p -> interviewerProfileRepository.findByUserId(p.getUser().getId()))
                .map(InterviewerProfile::getId)
                .orElse(null);
    }

    private List<UUID> otherRequiredParticipantIds(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED)
                .filter(p -> p.getParticipantRole() != ParticipantRole.CANDIDATE
                        && p.getParticipantRole() != ParticipantRole.INTERVIEWER)
                .map(p -> p.getUser().getId())
                .distinct()
                .toList();
    }
}
