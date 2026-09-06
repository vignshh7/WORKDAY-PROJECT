package com.interviewscheduler.interview;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarEvent;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.integration.CalendarEventStatus;
import com.interviewscheduler.notification.Notification;
import com.interviewscheduler.notification.NotificationChannel;
import com.interviewscheduler.notification.NotificationRepository;
import com.interviewscheduler.notification.NotificationStatus;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.scheduling.SchedulingRequest;
import com.interviewscheduler.scheduling.SchedulingResponse;
import com.interviewscheduler.scheduling.SchedulingService;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 17 — Participant Decline + Candidate Cancellation.
 *
 * <ul>
 *   <li>{@link #declineByInterviewer} — an assigned INTERVIEWER formally declines their
 *       participation. For SCHEDULED rounds the round moves to RESCHEDULE_REQUIRED and the
 *       scheduling engine is run to surface replacement options. For PENDING rounds the
 *       participant is simply marked DECLINED and no automatic rescheduling fires.</li>
 *   <li>{@link #cancelByCandidate} — the CANDIDATE cancels a round. The round moves to
 *       CANCELLED with no pipeline progression (candidate stage unchanged, no auto-reschedule).
 *       Rejected if the interview has already started (IN_PROGRESS).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ParticipantDeclineService {

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final CandidateRepository candidateRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final NotificationRepository notificationRepository;
    private final WorkingHoursService workingHoursService;
    private final SchedulingService schedulingService;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // Interviewer decline
    // -------------------------------------------------------------------------

    /**
     * An INTERVIEWER formally declines their assignment on a round.
     *
     * <ul>
     *   <li>SCHEDULED round → calendar cancelled, round RESCHEDULE_REQUIRED, reschedule count
     *       incremented, scheduling engine returns replacement slots.</li>
     *   <li>PENDING round → participant DECLINED; no time to cancel, no auto-reschedule.</li>
     * </ul>
     */
    @Transactional
    public SchedulingResponse declineByInterviewer(UUID roundId) {
        InterviewRound round = interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));

        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() != Role.INTERVIEWER) {
            throw new ForbiddenException("Only INTERVIEWER users may decline an assignment");
        }

        InterviewParticipant participant = interviewParticipantRepository
                .findByInterviewRoundIdAndUserId(roundId, caller.getId())
                .orElseThrow(() -> new ForbiddenException(
                        "You are not assigned as a participant on this interview"));
        if (participant.getParticipantRole() != ParticipantRole.INTERVIEWER) {
            throw new ForbiddenException("You are not assigned as an interviewer for this interview");
        }
        if (participant.getStatus() == ParticipantStatus.DECLINED
                || participant.getStatus() == ParticipantStatus.REMOVED) {
            throw new ConflictException("Your assignment is already " + participant.getStatus());
        }

        RoundStatus status = round.getStatus();
        if (status == RoundStatus.IN_PROGRESS || status == RoundStatus.COMPLETED
                || status == RoundStatus.CANCELLED) {
            throw new ConflictException("Cannot decline an interview in status: " + status);
        }
        if (status != RoundStatus.SCHEDULED && status != RoundStatus.PENDING
                && status != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException("Interviewer decline is not allowed for round status: " + status);
        }

        participant.setStatus(ParticipantStatus.DECLINED);
        interviewParticipantRepository.save(participant);

        // For PENDING rounds: no calendar to cancel, no scheduling needed
        if (status == RoundStatus.PENDING) {
            notifyActiveParticipants(round, NotificationType.INTERVIEWER_CANCELLED);
            auditService.logForCurrentUser(AuditAction.INTERVIEWER_DECLINED, "INTERVIEW_ROUND",
                    roundId, Map.of("declinedByUserId", caller.getId().toString(), "roundStatus", status.name()));
            return SchedulingResponse.of(List.of(), List.of());
        }

        // For SCHEDULED / RESCHEDULE_REQUIRED: check reschedule cap, cancel calendar, run engine
        SchedulingConfig config = workingHoursService.currentConfig();
        if (round.getRescheduleCount() >= config.getMaximumReschedules()) {
            throw new ConflictException("Maximum number of reschedules ("
                    + config.getMaximumReschedules() + ") has already been reached for this round");
        }

        cancelCalendarEvents(round);

        round.setScheduledStart(null);
        round.setScheduledEnd(null);
        round.setStatus(RoundStatus.RESCHEDULE_REQUIRED);
        round.setRescheduleCount((short) (round.getRescheduleCount() + 1));
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        notifyActiveParticipants(savedRound, NotificationType.INTERVIEWER_CANCELLED);
        auditService.logForCurrentUser(AuditAction.INTERVIEWER_DECLINED, "INTERVIEW_ROUND",
                savedRound.getId(), Map.of("declinedByUserId", caller.getId().toString()));

        // Run scheduling engine with no preferred interviewer — find qualified replacement
        Candidate candidate = savedRound.getProcess().getCandidate();
        List<UUID> otherIds = otherRequiredParticipantIds(savedRound);
        String timezone = savedRound.getTimezone() != null
                ? savedRound.getTimezone() : candidate.getUser().getTimezone();
        LocalDate dateFrom = LocalDate.now();
        LocalDate dateTo = dateFrom.plusDays(config.getMaximumSchedulingDays());

        SchedulingResponse response = schedulingService.recommend(new SchedulingRequest(
                candidate.getId(), savedRound.getId(), null, savedRound.getDurationMinutes(),
                dateFrom, dateTo, null, null, null, otherIds, timezone));

        if (!response.slots().isEmpty()) {
            auditService.logForCurrentUser(AuditAction.RESCHEDULING_SLOT_FOUND, "INTERVIEW_ROUND",
                    savedRound.getId(), Map.of("slotsFound", response.slots().size()));
        }
        return response;
    }

    // -------------------------------------------------------------------------
    // Candidate cancellation
    // -------------------------------------------------------------------------

    /**
     * The CANDIDATE cancels a round. Round → CANCELLED; candidate stage is NOT changed,
     * pipeline does NOT progress, no automatic reschedule.
     *
     * <p>Rejected if the interview has already started (IN_PROGRESS), is already completed,
     * or has already been cancelled.
     */
    @Transactional
    public InterviewRoundResponse cancelByCandidate(UUID roundId) {
        InterviewRound round = interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));

        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() != Role.CANDIDATE) {
            throw new ForbiddenException("Only CANDIDATE users may call this endpoint");
        }

        // Verify the caller is the candidate on this round's process
        Candidate candidate = candidateRepository.findByUserId(caller.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No candidate profile for the current user"));
        if (!round.getProcess().getCandidate().getId().equals(candidate.getId())) {
            throw new ForbiddenException("You can only cancel your own interview rounds");
        }

        RoundStatus status = round.getStatus();
        if (status == RoundStatus.IN_PROGRESS) {
            throw new ConflictException(
                    "Cannot cancel an interview that has already started (IN_PROGRESS)");
        }
        if (status == RoundStatus.COMPLETED || status == RoundStatus.CANCELLED) {
            throw new ConflictException("Cannot cancel a round with status: " + status);
        }

        cancelCalendarEvents(round);
        removeParticipants(round);

        round.setScheduledStart(null);
        round.setScheduledEnd(null);
        round.setStatus(RoundStatus.CANCELLED);
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        // Candidate stage is intentionally NOT changed — no progression
        notifyAllParticipants(savedRound, NotificationType.INTERVIEW_CANCELLED);
        auditService.logForCurrentUser(AuditAction.CANDIDATE_INTERVIEW_CANCELLED, "INTERVIEW_ROUND",
                savedRound.getId(), Map.of("cancelledByUserId", caller.getId().toString()));

        return InterviewRoundResponse.from(savedRound);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void cancelCalendarEvents(InterviewRound round) {
        for (CalendarEvent event : calendarEventRepository.findByInterviewRoundId(round.getId())) {
            if (event.getStatus() != CalendarEventStatus.CANCELLED) {
                event.setStatus(CalendarEventStatus.CANCELLED);
                calendarEventRepository.save(event);
            }
        }
    }

    private void removeParticipants(InterviewRound round) {
        for (InterviewParticipant p : interviewParticipantRepository.findByInterviewRoundId(round.getId())) {
            if (p.getStatus() != ParticipantStatus.REMOVED) {
                p.setStatus(ParticipantStatus.REMOVED);
                interviewParticipantRepository.save(p);
            }
        }
    }

    private void notifyActiveParticipants(InterviewRound round, NotificationType type) {
        interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED
                        && p.getStatus() != ParticipantStatus.DECLINED)
                .map(InterviewParticipant::getUser)
                .distinct()
                .forEach(user -> notify(user, round, type));
    }

    private void notifyAllParticipants(InterviewRound round, NotificationType type) {
        interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .map(InterviewParticipant::getUser)
                .distinct()
                .forEach(user -> notify(user, round, type));
    }

    private void notify(User user, InterviewRound round, NotificationType type) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setInterviewRound(round);
        notification.setType(type);
        notification.setChannel(NotificationChannel.IN_APP);
        notification.setStatus(NotificationStatus.PENDING);
        notificationRepository.save(notification);
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
