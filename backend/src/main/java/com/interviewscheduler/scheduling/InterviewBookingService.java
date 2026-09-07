package com.interviewscheduler.scheduling;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.availability.AvailabilityRepository;
import com.interviewscheduler.availability.AvailabilityStatus;
import com.interviewscheduler.availability.TimezoneService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.InvalidBookingException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.common.exception.SchedulingException;
import com.interviewscheduler.common.idempotency.IdempotencyService;
import com.interviewscheduler.integration.CalendarBusyTimeService;
import com.interviewscheduler.integration.CalendarEvent;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.integration.CalendarEventStatus;
import com.interviewscheduler.integration.CalendarSyncService;
import com.interviewscheduler.interview.InterviewParticipant;
import com.interviewscheduler.interview.InterviewParticipantRepository;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.ParticipantStatus;
import com.interviewscheduler.interview.RoundStatus;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.notification.NotificationService;
import com.interviewscheduler.notification.NotificationType;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import com.interviewscheduler.user.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Books an interview round: the flow the spec lists (authenticate/authorize happen in the
 * controller/security layer before this is ever called) - validate round/candidate/interviewer
 * -> validate participants/dependency -> fresh availability -> fresh conflict check ->
 * concurrency protection -> create/update participants -> scheduled times -> SCHEDULED ->
 * calendar record -> notifications -> audit -> commit.
 *
 * <h2>PostgreSQL transaction/locking strategy</h2>
 * The whole method is one {@code @Transactional} unit (default READ COMMITTED isolation -
 * Postgres's default, not changed here). Two {@code SELECT ... FOR UPDATE} row locks provide
 * concurrency protection:
 * <ul>
 *   <li>{@link InterviewRoundRepository#findByIdForUpdate} - a second transaction booking (or
 *   rescheduling/cancelling) the *same round* blocks here until the first commits or rolls
 *   back, then sees the round's now-current status and correctly refuses to double-book it.
 *   This is what "double click" and "two recruiters booking the same slot" (same round) reduce
 *   to.</li>
 *   <li>{@link InterviewerProfileRepository#findByIdForUpdate} - a second transaction booking
 *   the *same interviewer* on a *different* round (which the round-lock alone wouldn't catch,
 *   since it locks a different row) also blocks here, then its fresh conflict check correctly
 *   sees the first transaction's now-committed participant/round rows.</li>
 * </ul>
 * This does not lock the candidate or other required participants the same way - the fresh
 * conflict check still runs against them, but a true two-resource-at-once race on a shared
 * *candidate* (not the interviewer) is not fully serialized by a row lock here. In practice
 * this is a much narrower risk: a candidate can only have one ACTIVE process at a time (Phase
 * 7), so a genuine double-booking race for the same candidate would require two concurrent
 * booking attempts on two different rounds of that same single process - both would still have
 * to pass a fresh conflict check against each other's committed state, which the round lock's
 * serialization ordering (first transaction commits before the second's conflict check runs)
 * still protects against for any round it's scoped to.
 *
 * <p>Idempotency is a separate mechanism layered on top (see {@link IdempotencyService}): a
 * retried request with the same key returns the original response without re-running any of
 * this, rather than relying on the locks/state checks to make a retry merely harmless.
 */
@Service
@RequiredArgsConstructor
public class InterviewBookingService {

    private static final String IDEMPOTENCY_SCOPE = "BOOK_INTERVIEW";

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final CalendarSyncService calendarSyncService;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final AvailabilityRepository availabilityRepository;
    private final TimezoneService timezoneService;
    private final ConflictDetectionService conflictDetectionService;
    private final InterviewerMatchingService interviewerMatchingService;
    private final SchedulabilityGuard schedulabilityGuard;
    private final ParticipantRoleResolver participantRoleResolver;
    private final IdempotencyService idempotencyService;
    private final AuditService auditService;
    private final CalendarBusyTimeService calendarBusyTimeService;

    @Transactional
    public BookingResponse book(UUID roundId, BookingRequest request) {
        var replay = idempotencyService.claim(IDEMPOTENCY_SCOPE, request.idempotencyKey(), BookingResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (!request.end().isAfter(request.start())) {
            throw new InvalidBookingException("End time must be after start time");
        }

        InterviewRound round = interviewRoundRepository.findByIdForUpdate(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));
        Candidate candidate = round.getProcess().getCandidate();

        UserPrincipal caller = SecurityUtils.currentUser();
        if (caller.getRole() == Role.CANDIDATE && !candidate.getUser().getId().equals(caller.getId())) {
            throw new ForbiddenException("You can only book your own interview round");
        }

        schedulabilityGuard.validate(candidate, round);

        InterviewerProfile interviewer = interviewerProfileRepository.findByIdForUpdate(request.interviewerId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No interviewer with id: " + request.interviewerId()));
        if (interviewer.getUser().getStatus() != UserStatus.ACTIVE) {
            throw new ConflictException("Interviewer is not ACTIVE");
        }
        boolean eligible = interviewerMatchingService.match(round.getId()).eligibleInterviewers().stream()
                .anyMatch(m -> m.interviewerId().equals(interviewer.getId()));
        if (!eligible) {
            throw new ConflictException("Interviewer is not eligible for this round");
        }

        List<UUID> additionalParticipantIds = request.additionalParticipantIds() == null
                ? List.of() : request.additionalParticipantIds();
        List<CheckConflictsRequest.RequiredParticipant> requiredParticipants = additionalParticipantIds.stream()
                .map(participantRoleResolver::resolve)
                .toList();

        if (!isStillAvailable(candidate.getUser().getId(), request.start(), request.end())) {
            throw new SchedulingException("Candidate is no longer available for the proposed time", null);
        }
        if (!isStillAvailable(interviewer.getUser().getId(), request.start(), request.end())) {
            throw new SchedulingException("Interviewer is no longer available for the proposed time", null);
        }
        for (UUID participantId : additionalParticipantIds) {
            if (!isStillAvailable(participantId, request.start(), request.end())) {
                throw new SchedulingException(
                        "A required participant is no longer available for the proposed time", null);
            }
        }

        CheckConflictsRequest conflictRequest = new CheckConflictsRequest(
                round.getId(), request.start(), request.end(), interviewer.getId(), requiredParticipants);
        if (conflictDetectionService.checkConflicts(conflictRequest).hasConflicts()) {
            throw new SchedulingException("A fresh conflict check failed just before booking",
                    SchedulingReasonCode.ALL_SLOTS_CONFLICTED.name());
        }
        if (hasGoogleConflict(candidate.getUser().getId(), request.start(), request.end())) {
            throw new SchedulingException("Candidate's Google Calendar shows a conflict for the proposed time",
                    SchedulingReasonCode.ALL_SLOTS_CONFLICTED.name());
        }
        if (hasGoogleConflict(interviewer.getUser().getId(), request.start(), request.end())) {
            throw new SchedulingException("Interviewer's Google Calendar shows a conflict for the proposed time",
                    SchedulingReasonCode.ALL_SLOTS_CONFLICTED.name());
        }

        upsertParticipant(round, candidate.getUser(), ParticipantRole.CANDIDATE);
        upsertParticipant(round, interviewer.getUser(), ParticipantRole.INTERVIEWER);
        for (CheckConflictsRequest.RequiredParticipant participant : requiredParticipants) {
            upsertParticipant(round, userRepository.getReferenceById(participant.userId()), participant.role());
        }

        round.setScheduledStart(request.start());
        round.setScheduledEnd(request.end());
        round.setTimezone(request.timezone());
        round.setStatus(RoundStatus.SCHEDULED);
        InterviewRound savedRound = interviewRoundRepository.saveAndFlush(round);

        CalendarEvent calendarEvent = new CalendarEvent();
        calendarEvent.setInterviewRound(savedRound);
        calendarEvent.setStartTime(request.start());
        calendarEvent.setEndTime(request.end());
        calendarEvent.setStatus(CalendarEventStatus.PENDING);
        CalendarEvent savedEvent = calendarEventRepository.saveAndFlush(calendarEvent);
        calendarSyncService.syncCreate(savedEvent, calendarSyncService.attendeeEmails(savedRound.getId()));

        notify(candidate.getUser(), savedRound, NotificationType.INTERVIEW_SCHEDULED);
        notify(interviewer.getUser(), savedRound, NotificationType.INTERVIEW_SCHEDULED);

        auditService.logForCurrentUser(AuditAction.INTERVIEW_SCHEDULED, "INTERVIEW_ROUND", savedRound.getId(),
                Map.of("interviewerId", interviewer.getId(), "start", request.start().toString(),
                        "end", request.end().toString()));

        BookingResponse response = new BookingResponse(savedRound.getId(), savedRound.getStatus(),
                savedRound.getScheduledStart(), savedRound.getScheduledEnd(), interviewer.getId(),
                savedEvent.getId(), "Interview scheduled successfully");

        idempotencyService.complete(IDEMPOTENCY_SCOPE, request.idempotencyKey(), response);
        return response;
    }

    /**
     * Re-checks that some AVAILABLE window still fully contains the exact proposed window -
     * the slot may have been found minutes or days ago (find-slots/recommend) and the
     * underlying availability could have changed since; a partial overlap is not enough.
     */
    private boolean isStillAvailable(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        return availabilityRepository
                .findByUserIdAndDateBetween(userId, start.toLocalDate().minusDays(1), end.toLocalDate().plusDays(1))
                .stream()
                .filter(a -> a.getStatus() == AvailabilityStatus.AVAILABLE)
                .map(a -> timezoneService.toUtcRange(a.getDate(), a.getStartTime(), a.getEndTime(), a.getTimezone()))
                .anyMatch(range -> !range.start().isAfter(start.toInstant()) && !range.end().isBefore(end.toInstant()));
    }

    /**
     * Fresh, right-before-booking check against the user's own connected Google Calendar (in
     * addition to the DB-based {@link ConflictDetectionService} check above) - if they haven't
     * connected Google, this always reads as "no conflict" (see {@link CalendarBusyTimeService}),
     * exactly like {@link #isStillAvailable} narrows rather than replaces this system's own
     * {@code Availability} records.
     */
    private boolean hasGoogleConflict(UUID userId, OffsetDateTime start, OffsetDateTime end) {
        return calendarBusyTimeService.busyIntervals(userId, start, end).stream()
                .anyMatch(busy -> busy.start().isBefore(end) && busy.end().isAfter(start));
    }

    private void upsertParticipant(InterviewRound round, User user, ParticipantRole role) {
        InterviewParticipant participant = interviewParticipantRepository
                .findByInterviewRoundIdAndUserId(round.getId(), user.getId())
                .orElseGet(() -> {
                    InterviewParticipant created = new InterviewParticipant();
                    created.setInterviewRound(round);
                    created.setUser(user);
                    created.setParticipantRole(role);
                    return created;
                });
        participant.setStatus(ParticipantStatus.ASSIGNED);
        interviewParticipantRepository.save(participant);
    }

    private void notify(User user, InterviewRound round, NotificationType type) {
        notificationService.notify(user, round, type);
    }
}
