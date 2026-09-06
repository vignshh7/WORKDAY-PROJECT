package com.interviewscheduler.integration;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.exception.CalendarIntegrationException;
import com.interviewscheduler.interview.InterviewParticipant;
import com.interviewscheduler.interview.InterviewParticipantRepository;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.ParticipantStatus;
import com.interviewscheduler.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bridges the DB {@link CalendarEvent} record and the active {@link CalendarProvider}.
 *
 * <p>Per-user OAuth (Phase 20 revision): the interview event is created on the assigned
 * interviewer's own Google Calendar - not a shared org-wide account - with every other
 * participant (candidate, recruiter, hiring manager) invited by email address instead; they
 * don't need their own connection just to receive a calendar invite. The interviewer's user id
 * is recorded on the {@link CalendarEvent} row itself ({@code calendarOwnerUser}) so a later
 * update/cancel/reconciliation always uses the correct token even if the round's assigned
 * interviewer has since changed (e.g. a Phase 16 switch).
 *
 * <p>All methods run within the caller's existing {@code @Transactional} unit so that a
 * provider failure (→ {@link CalendarEventStatus#FAILED}) is committed atomically with the
 * booking that triggered it. The booking itself is never rolled back due to a calendar error —
 * {@link CalendarIntegrationException} is caught here, not rethrown.
 *
 * <p>Cancellations follow the same contract: if the provider call fails, the DB record is still
 * marked CANCELLED (the interview is cancelled regardless of whether the external event was
 * successfully deleted).
 */
@Service
@RequiredArgsConstructor
public class CalendarSyncService {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarProvider calendarProvider;
    private final CalendarEventRepository calendarEventRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final AuditService auditService;

    /**
     * Calls the provider to create a calendar event and updates the DB record with the result.
     * On provider failure the record is set to {@link CalendarEventStatus#FAILED} and a
     * {@link AuditAction#CALENDAR_SYNC_FAILED} log entry is written — the exception is not
     * rethrown so the enclosing booking transaction can commit.
     *
     * @param event         the just-saved {@link CalendarEvent} (status=PENDING)
     * @param attendeeEmails email addresses of all participants to invite
     */
    public void syncCreate(CalendarEvent event, List<String> attendeeEmails) {
        InterviewRound round = event.getInterviewRound();
        User calendarOwner = interviewerUserOf(round);
        event.setCalendarOwnerUser(calendarOwner);
        CalendarEventRequest request = new CalendarEventRequest(
                round.getId(),
                calendarOwner == null ? null : calendarOwner.getId(),
                buildTitle(round),
                buildDescription(round),
                event.getStartTime(),
                event.getEndTime(),
                round.getTimezone(),
                attendeeEmails
        );
        try {
            ExternalEventResult result = calendarProvider.createEvent(request);
            event.setExternalEventId(result.externalEventId());
            event.setMeetingLink(result.meetingLink());
            event.setProvider(calendarProvider.providerName());
            event.setStatus(CalendarEventStatus.CREATED);
            log.debug("Calendar event created: externalId={} provider={} roundId={}",
                    result.externalEventId(), calendarProvider.providerName(), round.getId());
        } catch (CalendarIntegrationException e) {
            log.warn("Calendar sync failed for roundId={}: {}", round.getId(), e.getMessage());
            event.setStatus(CalendarEventStatus.FAILED);
            auditService.log(null, ActorType.SYSTEM, AuditAction.CALENDAR_SYNC_FAILED,
                    "CALENDAR_EVENT", event.getId(), Map.of("error", e.getMessage()));
        }
        calendarEventRepository.save(event);
    }

    /**
     * Cancels all non-cancelled calendar events for the given round at both the provider and in
     * the DB. Provider errors are logged but do not prevent the DB record from being marked
     * CANCELLED — the interview cancellation proceeds regardless.
     */
    public void cancelAll(UUID roundId) {
        for (CalendarEvent event : calendarEventRepository.findByInterviewRoundId(roundId)) {
            if (event.getStatus() != CalendarEventStatus.CANCELLED) {
                cancelOne(event);
            }
        }
    }

    /**
     * Collects the email addresses of all currently-assigned (non-REMOVED) participants for a
     * round. Used by booking and switch-interviewer flows to populate provider attendee lists.
     */
    public List<String> attendeeEmails(UUID roundId) {
        return interviewParticipantRepository.findByInterviewRoundId(roundId).stream()
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED)
                .map(InterviewParticipant::getUser)
                .map(u -> u.getEmail())
                .distinct()
                .toList();
    }

    private void cancelOne(CalendarEvent event) {
        if (event.getExternalEventId() != null) {
            try {
                UUID ownerId = event.getCalendarOwnerUser() == null ? null : event.getCalendarOwnerUser().getId();
                calendarProvider.cancelEvent(event.getExternalEventId(), ownerId);
            } catch (CalendarIntegrationException e) {
                log.warn("Provider cancel failed for externalId={}: {}",
                        event.getExternalEventId(), e.getMessage());
                auditService.log(null, ActorType.SYSTEM, AuditAction.CALENDAR_SYNC_FAILED,
                        "CALENDAR_EVENT", event.getId(), Map.of("error", e.getMessage()));
            }
        }
        event.setStatus(CalendarEventStatus.CANCELLED);
        calendarEventRepository.save(event);
    }

    /** The round's currently-assigned (non-REMOVED) INTERVIEWER participant, if any. */
    private User interviewerUserOf(InterviewRound round) {
        return interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                .filter(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER && p.getStatus() != ParticipantStatus.REMOVED)
                .findFirst()
                .map(InterviewParticipant::getUser)
                .orElse(null);
    }

    private String buildTitle(InterviewRound round) {
        String candidateName = round.getProcess().getCandidate().getUser().getName();
        return round.getRoundType().name() + " Interview – " + candidateName;
    }

    private String buildDescription(InterviewRound round) {
        String jobTitle = round.getProcess().getJob().getTitle();
        return "Interview for the " + jobTitle + " position.";
    }
}
