package com.interviewscheduler.integration;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.interview.InterviewParticipantRepository;
import com.interviewscheduler.interview.InterviewReschedulingService;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.ParticipantStatus;
import com.interviewscheduler.interview.RoundStatus;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import com.interviewscheduler.scheduling.CheckConflictsRequest;
import com.interviewscheduler.scheduling.ConflictCheckResponse;
import com.interviewscheduler.scheduling.ConflictDetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reconciles exactly one calendar event, in its own {@code REQUIRES_NEW} transaction — split out
 * of {@link CalendarReconciliationService} into a separate bean specifically so that annotation
 * takes effect: Spring's proxy-based {@code @Transactional} is silently ignored on a
 * self-invoked method (calling {@code this.reconcileOne(...)} from another method in the same
 * class bypasses the proxy entirely), so the isolation this class exists for requires a genuine
 * cross-bean call from {@link CalendarReconciliationService}.
 */
@Service
@RequiredArgsConstructor
class SingleCalendarEventReconciler {

    private final CalendarEventRepository calendarEventRepository;
    private final CalendarProvider calendarProvider;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final ConflictDetectionService conflictDetectionService;
    private final InterviewReschedulingService reschedulingService;
    private final AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile(UUID calendarEventId) {
        CalendarEvent event = calendarEventRepository.findById(calendarEventId).orElse(null);
        if (event == null) {
            return;
        }
        InterviewRound round = event.getInterviewRound();
        if (round.getStatus() != RoundStatus.SCHEDULED || round.getScheduledStart() == null
                || !round.getScheduledStart().isAfter(OffsetDateTime.now())) {
            return;
        }

        CalendarEventSnapshot snapshot = calendarProvider.getEvent(event.getExternalEventId());

        if (!snapshot.exists()) {
            triggerReschedule(round, "Calendar event was deleted externally");
            return;
        }
        boolean timeChanged = snapshot.start() != null && snapshot.end() != null
                && (!snapshot.start().isEqual(round.getScheduledStart()) || !snapshot.end().isEqual(round.getScheduledEnd()));
        if (timeChanged) {
            triggerReschedule(round, "Calendar event time was changed externally");
            return;
        }
        if (!snapshot.declinedAttendeeEmails().isEmpty()) {
            triggerReschedule(round, "An attendee declined the calendar invite: " + snapshot.declinedAttendeeEmails());
            return;
        }

        ConflictCheckResponse conflicts = checkForNewConflict(round);
        if (conflicts.hasConflicts()) {
            triggerReschedule(round, "A new scheduling conflict was detected on reconciliation");
        }
    }

    private ConflictCheckResponse checkForNewConflict(InterviewRound round) {
        UUID interviewerProfileId = currentInterviewerProfileId(round);
        List<CheckConflictsRequest.RequiredParticipant> requiredParticipants = otherRequiredParticipants(round);
        return conflictDetectionService.checkConflicts(new CheckConflictsRequest(
                round.getId(), round.getScheduledStart(), round.getScheduledEnd(), interviewerProfileId, requiredParticipants));
    }

    private void triggerReschedule(InterviewRound round, String reason) {
        if (round.getStatus() != RoundStatus.SCHEDULED) {
            return;
        }
        auditService.log(null, ActorType.SYSTEM, AuditAction.CALENDAR_DRIFT_DETECTED,
                "INTERVIEW_ROUND", round.getId(), Map.of("reason", reason));
        reschedulingService.rescheduleSystemInitiated(round.getId(), reason);
    }

    private UUID currentInterviewerProfileId(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER && p.getStatus() != ParticipantStatus.REMOVED)
                .findFirst()
                .flatMap(p -> interviewerProfileRepository.findByUserId(p.getUser().getId()))
                .map(InterviewerProfile::getId)
                .orElse(null);
    }

    private List<CheckConflictsRequest.RequiredParticipant> otherRequiredParticipants(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED)
                .filter(p -> p.getParticipantRole() != ParticipantRole.CANDIDATE && p.getParticipantRole() != ParticipantRole.INTERVIEWER)
                .map(p -> new CheckConflictsRequest.RequiredParticipant(p.getUser().getId(), p.getParticipantRole()))
                .distinct()
                .toList();
    }
}
