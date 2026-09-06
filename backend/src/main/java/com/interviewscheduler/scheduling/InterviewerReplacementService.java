package com.interviewscheduler.scheduling;

import com.interviewscheduler.admin.ReplacementPolicy;
import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.availability.AvailabilityRepository;
import com.interviewscheduler.availability.AvailabilityStatus;
import com.interviewscheduler.availability.TimezoneService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.InvalidBookingException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.common.exception.SchedulingException;
import com.interviewscheduler.common.idempotency.IdempotencyService;
import com.interviewscheduler.integration.CalendarEvent;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.integration.CalendarEventStatus;
import com.interviewscheduler.integration.CalendarSyncService;
import com.interviewscheduler.interview.AssignmentType;
import com.interviewscheduler.interview.InterviewParticipant;
import com.interviewscheduler.interview.InterviewParticipantRepository;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.ParticipantStatus;
import com.interviewscheduler.interview.RoundStatus;
import com.interviewscheduler.interviewer.InterviewerMatchResult;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import com.interviewscheduler.notification.Notification;
import com.interviewscheduler.notification.NotificationChannel;
import com.interviewscheduler.notification.NotificationRepository;
import com.interviewscheduler.notification.NotificationStatus;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 16 — Backup + Replacement.
 *
 * <p>{@link #findReplacement} returns ranked replacement options in priority order:
 * <ol>
 *   <li>BACKUP_SAME_TIME — existing BACKUP participants available at the round's scheduled time</li>
 *   <li>QUALIFIED_SAME_TIME — other eligible interviewers available at the same time</li>
 *   <li>QUALIFIED_OTHER_TIME / ANY_FUTURE_SLOT — from the scheduling engine</li>
 * </ol>
 *
 * <p>{@link #switchInterviewer} executes the switch: removes the old interviewer participant,
 * adds the new one with REPLACEMENT assignment type, updates calendar and round state,
 * then notifies and audits.
 */
@Service
@RequiredArgsConstructor
public class InterviewerReplacementService {

    private static final String SWITCH_IDEMPOTENCY_SCOPE = "SWITCH_INTERVIEWER";

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final CalendarSyncService calendarSyncService;
    private final NotificationRepository notificationRepository;
    private final AvailabilityRepository availabilityRepository;
    private final TimezoneService timezoneService;
    private final ConflictDetectionService conflictDetectionService;
    private final InterviewerMatchingService interviewerMatchingService;
    private final SchedulingService schedulingService;
    private final WorkingHoursService workingHoursService;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;

    // -------------------------------------------------------------------------
    // find-replacement
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public FindReplacementResponse findReplacement(UUID roundId) {
        requireRecruiterOrAdmin();

        InterviewRound round = interviewRoundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));

        if (round.getStatus() != RoundStatus.SCHEDULED && round.getStatus() != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException(
                    "Replacement search requires the round to be SCHEDULED or RESCHEDULE_REQUIRED; current: "
                            + round.getStatus());
        }

        SchedulingConfig config = workingHoursService.currentConfig();

        // Current primary interviewer (may be absent if RESCHEDULE_REQUIRED and never booked)
        List<InterviewParticipant> participants = interviewParticipantRepository.findByInterviewRoundId(roundId);
        InterviewParticipant primaryInterviewer = primaryInterviewerOf(participants);

        Set<UUID> backupUserIds = participants.stream()
                .filter(p -> p.getAssignmentType() == AssignmentType.BACKUP
                        && p.getParticipantRole() == ParticipantRole.INTERVIEWER
                        && p.getStatus() != ParticipantStatus.REMOVED)
                .map(p -> p.getUser().getId())
                .collect(Collectors.toSet());

        // Eligible interviewers (by skill/domain/status) excluding the current primary
        UUID currentInterviewerUserId = primaryInterviewer != null
                ? primaryInterviewer.getUser().getId() : null;
        List<InterviewerMatchResult> eligible = interviewerMatchingService.match(roundId)
                .eligibleInterviewers().stream()
                .filter(m -> !m.userId().equals(currentInterviewerUserId))
                .toList();

        List<ReplacementOption> options = new ArrayList<>();

        if (round.getStatus() == RoundStatus.SCHEDULED
                && round.getScheduledStart() != null) {
            // Priority 1 & 2: same-time candidates
            for (InterviewerMatchResult match : eligible) {
                if (!isAvailableAt(match.userId(), round.getScheduledStart(), round.getScheduledEnd())) {
                    continue;
                }
                boolean isBackup = backupUserIds.contains(match.userId());
                options.add(new ReplacementOption(
                        match.interviewerId(),
                        match.name(),
                        round.getScheduledStart(),
                        round.getScheduledEnd(),
                        round.getTimezone(),
                        isBackup ? ReplacementPriority.BACKUP_SAME_TIME : ReplacementPriority.QUALIFIED_SAME_TIME,
                        match.score() != null ? match.score() : 0.0,
                        isBackup ? "Designated backup — available at the same scheduled time"
                                : "Qualified interviewer — available at the same scheduled time"
                ));
            }
            // Sort same-time: BACKUP before QUALIFIED, then by score
            options.sort((a, b) -> {
                if (a.priority() != b.priority()) {
                    return a.priority().ordinal() - b.priority().ordinal();
                }
                return Double.compare(b.score(), a.score());
            });
        }

        // Priority 3 & 4: scheduling engine for different-time / no-slot scenarios.
        // The engine's SchedulabilityGuard only accepts PENDING/RESCHEDULE_REQUIRED rounds;
        // for SCHEDULED rounds we return same-time options only (priorities 1 & 2 above).
        Candidate candidate = round.getProcess().getCandidate();
        SchedulingResponse engineResults;
        if (round.getStatus() == RoundStatus.RESCHEDULE_REQUIRED) {
            String timezone = round.getTimezone() != null
                    ? round.getTimezone() : candidate.getUser().getTimezone();
            LocalDate dateFrom = LocalDate.now();
            LocalDate dateTo = dateFrom.plusDays(config.getMaximumSchedulingDays());
            SchedulingRequest schedulingRequest = new SchedulingRequest(
                    candidate.getId(), roundId,
                    null, // no preferred interviewer — find qualified replacement
                    round.getDurationMinutes(),
                    dateFrom, dateTo,
                    null, null, null,
                    List.of(), timezone);
            engineResults = schedulingService.recommend(schedulingRequest);
        } else {
            engineResults = SchedulingResponse.of(List.of(), List.of());
        }

        // Collect same-time interviewer IDs already in options to avoid duplication
        Set<UUID> alreadyListed = options.stream()
                .map(ReplacementOption::interviewerId)
                .collect(Collectors.toSet());

        for (SlotResponse slot : engineResults.slots()) {
            if (alreadyListed.contains(slot.interviewerId())) {
                continue;
            }
            alreadyListed.add(slot.interviewerId());
            options.add(new ReplacementOption(
                    slot.interviewerId(), slot.interviewerName(),
                    slot.start(), slot.end(), slot.timezone(),
                    ReplacementPriority.QUALIFIED_OTHER_TIME,
                    slot.score(), slot.reason()
            ));
        }
        for (SlotResponse slot : engineResults.alternatives()) {
            if (alreadyListed.contains(slot.interviewerId())) {
                continue;
            }
            alreadyListed.add(slot.interviewerId());
            options.add(new ReplacementOption(
                    slot.interviewerId(), slot.interviewerName(),
                    slot.start(), slot.end(), slot.timezone(),
                    ReplacementPriority.ANY_FUTURE_SLOT,
                    slot.score(), slot.reason()
            ));
        }

        auditService.logForCurrentUser(AuditAction.REPLACEMENT_FOUND, "INTERVIEW_ROUND", roundId,
                Map.of("optionsFound", options.size()));

        InterviewerProfile currentProfile = primaryInterviewer != null
                ? interviewerProfileRepository.findByUserId(primaryInterviewer.getUser().getId()).orElse(null)
                : null;

        return new FindReplacementResponse(
                roundId,
                round.getStatus(),
                round.getScheduledStart(),
                round.getScheduledEnd(),
                currentProfile != null ? currentProfile.getId() : null,
                primaryInterviewer != null ? primaryInterviewer.getUser().getName() : null,
                config.getInterviewerReplacementPolicy(),
                options
        );
    }

    // -------------------------------------------------------------------------
    // switch-interviewer
    // -------------------------------------------------------------------------

    @Transactional
    public BookingResponse switchInterviewer(UUID roundId, SwitchInterviewerRequest request) {
        var replay = idempotencyService.claim(SWITCH_IDEMPOTENCY_SCOPE, request.idempotencyKey(),
                BookingResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }

        requireRecruiterOrAdmin();

        InterviewRound round = interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));

        if (round.getStatus() != RoundStatus.SCHEDULED && round.getStatus() != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException(
                    "Interviewer switch requires the round to be SCHEDULED or RESCHEDULE_REQUIRED; current: "
                            + round.getStatus());
        }

        SchedulingConfig config = workingHoursService.currentConfig();
        if (round.getRescheduleCount() >= config.getMaximumReschedules()) {
            throw new ConflictException("Maximum number of reschedules ("
                    + config.getMaximumReschedules() + ") has already been reached for this round");
        }

        // New interviewer
        InterviewerProfile newInterviewer = interviewerProfileRepository
                .findByIdForUpdate(request.newInterviewerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No interviewer with id: " + request.newInterviewerId()));
        if (newInterviewer.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ConflictException("Replacement interviewer is not ACTIVE");
        }

        boolean eligible = interviewerMatchingService.match(roundId).eligibleInterviewers().stream()
                .anyMatch(m -> m.interviewerId().equals(newInterviewer.getId()));
        if (!eligible) {
            throw new ConflictException("Replacement interviewer is not eligible for this round");
        }

        // Determine new start/end: use request values if provided, else keep current scheduled time
        OffsetDateTime newStart;
        OffsetDateTime newEnd;
        String newTimezone;

        if (request.start() != null && request.end() != null) {
            if (!request.end().isAfter(request.start())) {
                throw new InvalidBookingException("End time must be after start time");
            }
            newStart = request.start();
            newEnd = request.end();
            newTimezone = request.timezone() != null ? request.timezone()
                    : (round.getTimezone() != null ? round.getTimezone()
                    : round.getProcess().getCandidate().getUser().getTimezone());
        } else if (round.getStatus() == RoundStatus.SCHEDULED && round.getScheduledStart() != null) {
            newStart = round.getScheduledStart();
            newEnd = round.getScheduledEnd();
            newTimezone = round.getTimezone() != null ? round.getTimezone()
                    : round.getProcess().getCandidate().getUser().getTimezone();
        } else {
            throw new ConflictException(
                    "Round has no scheduled time; provide start and end times in the request");
        }

        // Fresh availability + conflict checks for the new interviewer
        if (!isAvailableAt(newInterviewer.getUser().getId(), newStart, newEnd)) {
            throw new SchedulingException(
                    "Replacement interviewer is not available at the proposed time", null);
        }

        CheckConflictsRequest conflictRequest = new CheckConflictsRequest(
                roundId, newStart, newEnd, newInterviewer.getId(), List.of());
        if (conflictDetectionService.checkConflicts(conflictRequest).hasConflicts()) {
            throw new SchedulingException(
                    "Conflict detected for the replacement interviewer at the proposed time", null);
        }

        // Remove existing primary/replacement interviewer participants for this round
        List<InterviewParticipant> participants = interviewParticipantRepository.findByInterviewRoundId(roundId);
        for (InterviewParticipant p : participants) {
            if (p.getParticipantRole() == ParticipantRole.INTERVIEWER
                    && p.getStatus() != ParticipantStatus.REMOVED) {
                p.setStatus(ParticipantStatus.REMOVED);
                interviewParticipantRepository.save(p);
            }
        }

        // Add new interviewer with REPLACEMENT assignment type (or PRIMARY if round had no prior assignment)
        AssignmentType assignType = participants.stream()
                .anyMatch(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER)
                ? AssignmentType.REPLACEMENT : AssignmentType.PRIMARY;

        InterviewParticipant newParticipant = interviewParticipantRepository
                .findByInterviewRoundIdAndUserId(roundId, newInterviewer.getUser().getId())
                .orElseGet(() -> {
                    InterviewParticipant p = new InterviewParticipant();
                    p.setInterviewRound(round);
                    p.setUser(newInterviewer.getUser());
                    p.setParticipantRole(ParticipantRole.INTERVIEWER);
                    return p;
                });
        newParticipant.setAssignmentType(assignType);
        newParticipant.setStatus(ParticipantStatus.ASSIGNED);
        interviewParticipantRepository.save(newParticipant);

        // Cancel old calendar events
        calendarSyncService.cancelAll(roundId);

        // Update round state
        boolean timeChanged = !newStart.equals(round.getScheduledStart());
        round.setScheduledStart(newStart);
        round.setScheduledEnd(newEnd);
        round.setTimezone(newTimezone);
        round.setStatus(RoundStatus.SCHEDULED);
        round.setRescheduleCount((short) (round.getRescheduleCount() + 1));
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        // New calendar event
        CalendarEvent calendarEvent = new CalendarEvent();
        calendarEvent.setInterviewRound(savedRound);
        calendarEvent.setStartTime(newStart);
        calendarEvent.setEndTime(newEnd);
        calendarEvent.setStatus(CalendarEventStatus.PENDING);
        CalendarEvent savedEvent = calendarEventRepository.saveAndFlush(calendarEvent);
        calendarSyncService.syncCreate(savedEvent, calendarSyncService.attendeeEmails(savedRound.getId()));

        // Notify active participants
        Candidate candidate = savedRound.getProcess().getCandidate();
        notifyUser(candidate.getUser(), savedRound, NotificationType.INTERVIEW_RESCHEDULED);
        notifyUser(newInterviewer.getUser(), savedRound, NotificationType.INTERVIEW_SCHEDULED);

        // Audit
        Map<String, Object> auditDetails = new java.util.LinkedHashMap<>();
        auditDetails.put("newInterviewerId", newInterviewer.getId().toString());
        auditDetails.put("newStart", newStart.toString());
        auditDetails.put("newEnd", newEnd.toString());
        auditDetails.put("timeChanged", timeChanged);
        auditService.logForCurrentUser(AuditAction.INTERVIEWER_REPLACED, "INTERVIEW_ROUND",
                savedRound.getId(), auditDetails);
        if (timeChanged) {
            auditService.logForCurrentUser(AuditAction.INTERVIEW_RESCHEDULED, "INTERVIEW_ROUND",
                    savedRound.getId(), Map.of("newStart", newStart.toString(), "newEnd", newEnd.toString()));
        }

        BookingResponse response = new BookingResponse(
                savedRound.getId(), savedRound.getStatus(),
                savedRound.getScheduledStart(), savedRound.getScheduledEnd(),
                newInterviewer.getId(), savedEvent.getId(),
                "Interviewer replaced successfully");

        idempotencyService.complete(SWITCH_IDEMPOTENCY_SCOPE, request.idempotencyKey(), response);
        return response;
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private InterviewParticipant primaryInterviewerOf(List<InterviewParticipant> participants) {
        return participants.stream()
                .filter(p -> p.getParticipantRole() == ParticipantRole.INTERVIEWER)
                .filter(p -> p.getStatus() != ParticipantStatus.REMOVED
                        && p.getStatus() != ParticipantStatus.DECLINED)
                .findFirst()
                .orElse(null);
    }

    private boolean isAvailableAt(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        return availabilityRepository
                .findByUserIdAndDateBetween(userId,
                        start.toLocalDate().minusDays(1), end.toLocalDate().plusDays(1))
                .stream()
                .filter(a -> a.getStatus() == AvailabilityStatus.AVAILABLE)
                .map(a -> timezoneService.toUtcRange(
                        a.getDate(), a.getStartTime(), a.getEndTime(), a.getTimezone()))
                .anyMatch(range -> !range.start().isAfter(start.toInstant())
                        && !range.end().isBefore(end.toInstant()));
    }

    private void notifyUser(User user, InterviewRound round, NotificationType type) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setInterviewRound(round);
        notification.setType(type);
        notification.setChannel(NotificationChannel.IN_APP);
        notification.setStatus(NotificationStatus.PENDING);
        notificationRepository.save(notification);
    }

    private void requireRecruiterOrAdmin() {
        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() != Role.RECRUITER && caller.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Only RECRUITER or ADMIN may manage interviewer replacements");
        }
    }
}
