package com.interviewscheduler.scheduling;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.availability.AvailabilityRepository;
import com.interviewscheduler.availability.AvailabilityStatus;
import com.interviewscheduler.availability.TimezoneService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarBusyTimeService;
import com.interviewscheduler.integration.GoogleOAuthTokenService;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interviewer.InterviewerMatchResult;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Deterministic feasibility search (spec steps 1-18). No AI, no ranking (step 19 is
 * {@link SlotRankingService}) - this only decides what is structurally bookable.
 */
@Service
@RequiredArgsConstructor
public class SlotFinderService {

    private static final Logger PERF_LOG = LoggerFactory.getLogger("PERF." + SlotFinderService.class.getName());

    /** Candidate start times are tried at this stride within a common availability window. */
    private static final long STEP_MINUTES = 30;
    /** Caps how many candidate starts are tried per common window, to bound the search. */
    private static final int MAX_STARTS_PER_WINDOW = 6;

    private final CandidateRepository candidateRepository;
    private final InterviewRoundRepository interviewRoundRepository;
    private final AvailabilityRepository availabilityRepository;
    private final InterviewerMatchingService interviewerMatchingService;
    private final ConflictDetectionService conflictDetectionService;
    private final WorkingHoursService workingHoursService;
    private final TimezoneService timezoneService;
    private final SchedulabilityGuard schedulabilityGuard;
    private final ParticipantRoleResolver participantRoleResolver;
    private final CalendarBusyTimeService calendarBusyTimeService;
    private final GoogleOAuthTokenService googleOAuthTokenService;
    private final UserRepository userRepository;

    @Value("${calendar.provider:noop}")
    private String activeCalendarProvider;

    @Transactional(readOnly = true)
    public SchedulingResponse findSlots(SchedulingRequest request) {
        Candidate candidate = candidateRepository.findById(request.candidateId())
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + request.candidateId()));
        InterviewRound round = interviewRoundRepository.findById(request.roundId())
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + request.roundId()));
        if (!round.getProcess().getCandidate().getId().equals(candidate.getId())) {
            throw new ResourceNotFoundException(
                    "Round " + round.getId() + " does not belong to candidate " + candidate.getId());
        }
        schedulabilityGuard.validate(candidate, round);

        List<InterviewerMatchResult> eligibleInterviewers =
                interviewerMatchingService.match(round.getId()).eligibleInterviewers();
        if (eligibleInterviewers.isEmpty()) {
            return SchedulingResponse.empty(SchedulingReasonCode.NO_QUALIFIED_INTERVIEWER);
        }

        List<UUID> requiredParticipantIds = request.requiredParticipantIds() == null
                ? List.of() : request.requiredParticipantIds();
        List<CheckConflictsRequest.RequiredParticipant> requiredParticipants = requiredParticipantIds.stream()
                .map(participantRoleResolver::resolve)
                .toList();

        // Every participant's window is independent of every other's, and for a Google-connected
        // user it costs a blocking freebusy HTTP call (GoogleOAuthTokenService's REQUIRES_NEW
        // transactions make each of these calls self-contained, so running them concurrently
        // instead of one after another is safe). Sequentially, a handful of connected
        // interviewers pushed this well past a minute; in parallel it's bounded by the slowest
        // single call instead of their sum.
        UUID candidateUserId = candidate.getUser().getId();
        long t0 = System.currentTimeMillis();
        Map<UUID, List<TimezoneService.TimeRange>> windowsByUserId =
                fetchWindowsConcurrently(candidateUserId, requiredParticipantIds, eligibleInterviewers, request);
        PERF_LOG.info("fetchWindowsConcurrently took {}ms for {} participants",
                System.currentTimeMillis() - t0, windowsByUserId.size());

        List<TimezoneService.TimeRange> candidateWindows = windowsByUserId.get(candidateUserId);
        if (candidateWindows.isEmpty()) {
            return SchedulingResponse.empty(SchedulingReasonCode.NO_CANDIDATE_AVAILABILITY);
        }

        List<TimezoneService.TimeRange> baseCommon = candidateWindows;
        for (UUID participantId : requiredParticipantIds) {
            baseCommon = intersect(baseCommon, windowsByUserId.get(participantId));
            if (baseCommon.isEmpty()) {
                break;
            }
        }

        SchedulingConfig config = workingHoursService.currentConfig();
        Duration duration = Duration.ofMinutes(request.durationMinutes());
        ZoneId zone = ZoneId.of(request.timezone());

        boolean anyInterviewerHasAvailability = false;
        boolean anyCommonWindow = false;
        boolean anyPassedDateTimeFilters = false;
        boolean anyPassedWorkingHours = false;
        List<SlotResponse> validSlots = new ArrayList<>();

        for (InterviewerMatchResult interviewer : eligibleInterviewers) {
            List<TimezoneService.TimeRange> interviewerWindows = windowsByUserId.get(interviewer.userId());
            if (interviewerWindows.isEmpty()) {
                continue;
            }
            anyInterviewerHasAvailability = true;

            List<TimezoneService.TimeRange> common = intersect(baseCommon, interviewerWindows);
            if (common.isEmpty()) {
                continue;
            }
            anyCommonWindow = true;

            for (TimezoneService.TimeRange window : common) {
                for (Instant start : candidateStarts(window, duration)) {
                    Instant end = start.plus(duration);
                    ZonedDateTime startLocal = start.atZone(zone);
                    ZonedDateTime endLocal = end.atZone(zone);

                    if (!passesDateTimeFilters(request, config, start, startLocal, endLocal)) {
                        continue;
                    }
                    anyPassedDateTimeFilters = true;

                    if (!workingHoursService.isWithinWorkingHours(startLocal.toLocalTime(), endLocal.toLocalTime())) {
                        continue;
                    }
                    anyPassedWorkingHours = true;

                    CheckConflictsRequest conflictRequest = new CheckConflictsRequest(
                            round.getId(), startLocal.toOffsetDateTime(), endLocal.toOffsetDateTime(),
                            interviewer.interviewerId(), requiredParticipants);
                    if (conflictDetectionService.checkConflicts(conflictRequest).hasConflicts()) {
                        continue;
                    }

                    validSlots.add(new SlotResponse(interviewer.interviewerId(), interviewer.name(),
                            startLocal.toOffsetDateTime(), endLocal.toOffsetDateTime(), request.timezone(),
                            interviewer.score(), "Candidate, interviewer, and required participants all available"));
                }
            }
        }

        if (validSlots.isEmpty()) {
            if (!anyInterviewerHasAvailability) {
                return SchedulingResponse.empty(SchedulingReasonCode.NO_INTERVIEWER_AVAILABILITY);
            }
            if (!anyCommonWindow) {
                return SchedulingResponse.empty(SchedulingReasonCode.NO_COMMON_SLOT);
            }
            if (!anyPassedDateTimeFilters) {
                return SchedulingResponse.empty(SchedulingReasonCode.DATE_RANGE_EXHAUSTED);
            }
            if (!anyPassedWorkingHours) {
                return SchedulingResponse.empty(SchedulingReasonCode.OUTSIDE_WORKING_HOURS);
            }
            return SchedulingResponse.empty(SchedulingReasonCode.ALL_SLOTS_CONFLICTED);
        }

        List<SlotResponse> sorted = validSlots.stream()
                .sorted(Comparator.comparing(SlotResponse::start))
                .toList();
        return SchedulingResponse.of(sorted, List.of());
    }

    private boolean passesDateTimeFilters(SchedulingRequest request, SchedulingConfig config, Instant start,
                                           ZonedDateTime startLocal, ZonedDateTime endLocal) {
        Instant noticeLimit = Instant.now().plus(config.getMinimumBookingNoticeMinutes(), ChronoUnit.MINUTES);
        if (start.isBefore(noticeLimit)) {
            return false;
        }
        Instant horizonLimit = Instant.now().plus(config.getMaximumSchedulingDays(), ChronoUnit.DAYS);
        if (start.isAfter(horizonLimit)) {
            return false;
        }
        DayOfWeek dayOfWeek = startLocal.getDayOfWeek();
        if (workingHoursService.isWeekend(startLocal.toLocalDate()) && !workingHoursService.weekendsAllowed()) {
            return false;
        }
        if (request.excludedDays() != null && request.excludedDays().contains(dayOfWeek)) {
            return false;
        }
        if (request.preferredTimeStart() != null) {
            LocalTime startTime = startLocal.toLocalTime();
            LocalTime endTime = endLocal.toLocalTime();
            if (startTime.isBefore(request.preferredTimeStart())) {
                return false;
            }
            if (request.preferredTimeEnd() != null && endTime.isAfter(request.preferredTimeEnd())) {
                return false;
            }
        }
        return true;
    }

    private List<Instant> candidateStarts(TimezoneService.TimeRange window, Duration duration) {
        List<Instant> starts = new ArrayList<>();
        Instant cursor = window.start();
        int count = 0;
        while (!cursor.plus(duration).isAfter(window.end()) && count < MAX_STARTS_PER_WINDOW) {
            starts.add(cursor);
            cursor = cursor.plus(STEP_MINUTES, ChronoUnit.MINUTES);
            count++;
        }
        return starts;
    }

    /**
     * Resolves {@link #availableWindowsFor} for the candidate, every required participant, and
     * every eligible interviewer concurrently instead of one at a time. Runs on virtual threads
     * (this is IO-bound waiting on Google's API, not CPU-bound) so no pool sizing is needed, and
     * each task opens its own DB access on its own thread rather than sharing the caller's
     * {@code @Transactional(readOnly = true)} session, which is never touched off-thread here.
     */
    private Map<UUID, List<TimezoneService.TimeRange>> fetchWindowsConcurrently(
            UUID candidateUserId, List<UUID> requiredParticipantIds,
            List<InterviewerMatchResult> eligibleInterviewers, SchedulingRequest request) {
        Map<UUID, CompletableFuture<List<TimezoneService.TimeRange>>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures.put(candidateUserId,
                    CompletableFuture.supplyAsync(() -> availableWindowsFor(candidateUserId, request), executor));
            for (UUID participantId : requiredParticipantIds) {
                futures.computeIfAbsent(participantId,
                        id -> CompletableFuture.supplyAsync(() -> availableWindowsFor(id, request), executor));
            }
            for (InterviewerMatchResult interviewer : eligibleInterviewers) {
                futures.computeIfAbsent(interviewer.userId(),
                        id -> CompletableFuture.supplyAsync(() -> availableWindowsFor(id, request), executor));
            }
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            // Unwrap so callers (and GlobalExceptionHandler) see the real exception type, not a
            // CompletionException wrapper that would otherwise map to a generic 500.
            switch (e.getCause()) {
                case RuntimeException re -> throw re;
                case null -> throw e;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
        Map<UUID, List<TimezoneService.TimeRange>> result = new LinkedHashMap<>();
        futures.forEach((userId, future) -> result.put(userId, future.join()));
        return result;
    }

    /**
     * A user connected to Google Calendar gets their availability computed, not declared: the
     * organization's configured working hours (scheduling_config), expressed in that specific
     * user's own stored timezone, minus whatever their Google Calendar reports as busy in that
     * window - manual {@code Availability} rows are neither required nor consulted for them,
     * since a connected calendar is a strictly more complete and lower-effort source of truth.
     * A user who hasn't connected falls back to the pre-Phase-20 manual-entry path (their own
     * declared {@code Availability} AVAILABLE rows) - there is no other source of their free time.
     * Called for the candidate, every required participant, and each eligible interviewer, so
     * "combine both with application availability... find only slots valid for both" falls out
     * of the existing common-window intersection below without any special-casing per role.
     */
    private List<TimezoneService.TimeRange> availableWindowsFor(UUID userId, SchedulingRequest request) {
        long t0 = System.currentTimeMillis();
        if ("google".equalsIgnoreCase(activeCalendarProvider)
                && googleOAuthTokenService.currentConnection(userId).isPresent()) {
            List<TimezoneService.TimeRange> result = workingHoursMinusGoogleBusy(userId, request);
            PERF_LOG.info("availableWindowsFor(google) userId={} took {}ms", userId, System.currentTimeMillis() - t0);
            return result;
        }
        return availabilityRepository
                .findByUserIdAndDateBetween(userId, request.dateFrom(), request.dateTo())
                .stream()
                .filter(a -> a.getStatus() == AvailabilityStatus.AVAILABLE)
                .map(a -> timezoneService.toUtcRange(a.getDate(), a.getStartTime(), a.getEndTime(), a.getTimezone()))
                .sorted(Comparator.comparing(TimezoneService.TimeRange::start))
                .toList();
    }

    /** Work-hours windows (one per eligible day in the request's date range, in this user's own
     * timezone) minus their Google Calendar busy time - see {@link #availableWindowsFor}. */
    private List<TimezoneService.TimeRange> workingHoursMinusGoogleBusy(UUID userId, SchedulingRequest request) {
        String zoneId = userRepository.findById(userId).map(User::getTimezone).orElse("UTC");
        SchedulingConfig config = workingHoursService.currentConfig();
        List<TimezoneService.TimeRange> windows = new ArrayList<>();
        for (LocalDate date = request.dateFrom(); !date.isAfter(request.dateTo()); date = date.plusDays(1)) {
            if (workingHoursService.isWeekend(date) && !config.isAllowWeekends()) {
                continue;
            }
            windows.add(timezoneService.toUtcRange(date, config.getWorkingStart(), config.getWorkingEnd(), zoneId));
        }
        if (windows.isEmpty()) {
            return windows;
        }
        List<TimezoneService.TimeRange> busy = googleBusyWindows(userId, request);
        return busy.isEmpty() ? windows : subtractBusy(windows, busy);
    }

    private List<TimezoneService.TimeRange> googleBusyWindows(UUID userId, SchedulingRequest request) {
        OffsetDateTime from = OffsetDateTime.of(request.dateFrom().minusDays(1).atStartOfDay(), ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(request.dateTo().plusDays(2).atStartOfDay(), ZoneOffset.UTC);
        return calendarBusyTimeService.busyIntervals(userId, from, to).stream()
                .map(b -> new TimezoneService.TimeRange(b.start().toInstant(), b.end().toInstant()))
                .toList();
    }

    /** Subtracts busy intervals from a sorted, internally non-overlapping window list. */
    private List<TimezoneService.TimeRange> subtractBusy(List<TimezoneService.TimeRange> windows,
                                                           List<TimezoneService.TimeRange> busy) {
        List<TimezoneService.TimeRange> result = new ArrayList<>();
        for (TimezoneService.TimeRange window : windows) {
            List<TimezoneService.TimeRange> pieces = List.of(window);
            for (TimezoneService.TimeRange busyRange : busy) {
                List<TimezoneService.TimeRange> next = new ArrayList<>();
                for (TimezoneService.TimeRange piece : pieces) {
                    next.addAll(subtractOne(piece, busyRange));
                }
                pieces = next;
            }
            result.addAll(pieces);
        }
        return result;
    }

    /** {@code window} minus {@code busy}: 0, 1, or 2 resulting pieces depending on overlap. */
    private List<TimezoneService.TimeRange> subtractOne(TimezoneService.TimeRange window, TimezoneService.TimeRange busy) {
        Instant overlapStart = window.start().isAfter(busy.start()) ? window.start() : busy.start();
        Instant overlapEnd = window.end().isBefore(busy.end()) ? window.end() : busy.end();
        if (!overlapStart.isBefore(overlapEnd)) {
            return List.of(window);
        }
        List<TimezoneService.TimeRange> pieces = new ArrayList<>();
        if (overlapStart.isAfter(window.start())) {
            pieces.add(new TimezoneService.TimeRange(window.start(), overlapStart));
        }
        if (overlapEnd.isBefore(window.end())) {
            pieces.add(new TimezoneService.TimeRange(overlapEnd, window.end()));
        }
        return pieces;
    }

    /** Pairwise intersection of two sorted, internally non-overlapping interval lists. */
    private List<TimezoneService.TimeRange> intersect(List<TimezoneService.TimeRange> a,
                                                        List<TimezoneService.TimeRange> b) {
        List<TimezoneService.TimeRange> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            TimezoneService.TimeRange x = a.get(i);
            TimezoneService.TimeRange y = b.get(j);
            Instant start = x.start().isAfter(y.start()) ? x.start() : y.start();
            Instant end = x.end().isBefore(y.end()) ? x.end() : y.end();
            if (start.isBefore(end)) {
                result.add(new TimezoneService.TimeRange(start, end));
            }
            if (x.end().isBefore(y.end())) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }
}
