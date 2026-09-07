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
                fetchWindowsConcurrently(candidateUserId, requiredParticipantIds, eligibleInterviewers, round, request);
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

        long t1 = System.currentTimeMillis();
        List<PerInterviewerResult> perInterviewerResults = computeSlotsConcurrently(
                eligibleInterviewers, windowsByUserId, baseCommon, request, config, duration, zone);
        PERF_LOG.info("computeSlotsConcurrently took {}ms for {} interviewers",
                System.currentTimeMillis() - t1, eligibleInterviewers.size());

        boolean anyInterviewerHasAvailability = false;
        boolean anyCommonWindow = false;
        boolean anyPassedDateTimeFilters = false;
        boolean anyPassedWorkingHours = false;
        List<SlotResponse> validSlots = new ArrayList<>();
        for (PerInterviewerResult result : perInterviewerResults) {
            anyInterviewerHasAvailability |= result.hasAvailability();
            anyCommonWindow |= result.hasCommonWindow();
            anyPassedDateTimeFilters |= result.passedDateTimeFilters();
            anyPassedWorkingHours |= result.passedWorkingHours();
            validSlots.addAll(result.slots());
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
            List<InterviewerMatchResult> eligibleInterviewers, InterviewRound round, SchedulingRequest request) {
        Map<UUID, CompletableFuture<List<TimezoneService.TimeRange>>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures.put(candidateUserId,
                    CompletableFuture.supplyAsync(() -> availableWindowsFor(candidateUserId, round, request), executor));
            for (UUID participantId : requiredParticipantIds) {
                futures.computeIfAbsent(participantId,
                        id -> CompletableFuture.supplyAsync(() -> availableWindowsFor(id, round, request), executor));
            }
            for (InterviewerMatchResult interviewer : eligibleInterviewers) {
                futures.computeIfAbsent(interviewer.userId(),
                        id -> CompletableFuture.supplyAsync(() -> availableWindowsFor(id, round, request), executor));
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

    private record PerInterviewerResult(boolean hasAvailability, boolean hasCommonWindow,
                                         boolean passedDateTimeFilters, boolean passedWorkingHours,
                                         List<SlotResponse> slots) {
    }

    /**
     * Evaluates every eligible interviewer concurrently instead of one at a time. Each
     * interviewer's window intersection and candidate-start enumeration is completely
     * independent of every other interviewer's. This used to also run a DB-backed {@link
     * ConflictDetectionService#checkConflicts} per candidate start — with a wide interviewer
     * pool that dominated even after {@link #fetchWindowsConcurrently} made the availability
     * lookups themselves fast — but every scheduled-round conflict it checked is now already
     * excluded from {@code windowsByUserId} up front (see {@link #existingRoundBusyWindows}),
     * so this loop is pure in-memory interval math and the concurrency here mainly protects
     * against a pathologically wide interviewer pool rather than DB latency.
     */
    private List<PerInterviewerResult> computeSlotsConcurrently(
            List<InterviewerMatchResult> eligibleInterviewers,
            Map<UUID, List<TimezoneService.TimeRange>> windowsByUserId,
            List<TimezoneService.TimeRange> baseCommon,
            SchedulingRequest request, SchedulingConfig config, Duration duration, ZoneId zone) {
        List<CompletableFuture<PerInterviewerResult>> futures;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = eligibleInterviewers.stream()
                    .map(interviewer -> CompletableFuture.supplyAsync(
                            () -> computeSlotsForInterviewer(interviewer, windowsByUserId.get(interviewer.userId()),
                                    baseCommon, request, config, duration, zone),
                            executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            switch (e.getCause()) {
                case RuntimeException re -> throw re;
                case null -> throw e;
                default -> throw new IllegalStateException(e.getCause());
            }
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private PerInterviewerResult computeSlotsForInterviewer(
            InterviewerMatchResult interviewer, List<TimezoneService.TimeRange> interviewerWindows,
            List<TimezoneService.TimeRange> baseCommon,
            SchedulingRequest request, SchedulingConfig config, Duration duration, ZoneId zone) {
        List<SlotResponse> slots = new ArrayList<>();
        if (interviewerWindows.isEmpty()) {
            return new PerInterviewerResult(false, false, false, false, slots);
        }

        List<TimezoneService.TimeRange> common = intersect(baseCommon, interviewerWindows);
        if (common.isEmpty()) {
            return new PerInterviewerResult(true, false, false, false, slots);
        }

        boolean passedDateTimeFilters = false;
        boolean passedWorkingHours = false;
        for (TimezoneService.TimeRange window : common) {
            for (Instant start : candidateStarts(window, duration)) {
                Instant end = start.plus(duration);
                ZonedDateTime startLocal = start.atZone(zone);
                ZonedDateTime endLocal = end.atZone(zone);

                if (!passesDateTimeFilters(request, config, start, startLocal, endLocal)) {
                    continue;
                }
                passedDateTimeFilters = true;

                if (!workingHoursService.isWithinWorkingHours(startLocal.toLocalTime(), endLocal.toLocalTime())) {
                    continue;
                }
                passedWorkingHours = true;

                slots.add(new SlotResponse(interviewer.interviewerId(), interviewer.name(),
                        startLocal.toOffsetDateTime(), endLocal.toOffsetDateTime(), request.timezone(),
                        interviewer.score(), "Candidate, interviewer, and required participants all available"));
            }
        }
        return new PerInterviewerResult(true, true, passedDateTimeFilters, passedWorkingHours, slots);
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
     *
     * <p>Either way, the result also has this user's other already-scheduled interview rounds
     * (buffer-expanded) subtracted out — see {@link #existingRoundBusyWindows} — so the search
     * loop never has to run a DB-backed conflict check per candidate start; a slot simply isn't
     * offered if it would conflict.
     */
    private List<TimezoneService.TimeRange> availableWindowsFor(UUID userId, InterviewRound round,
                                                                  SchedulingRequest request) {
        long t0 = System.currentTimeMillis();
        List<TimezoneService.TimeRange> base;
        boolean google = "google".equalsIgnoreCase(activeCalendarProvider)
                && googleOAuthTokenService.currentConnection(userId).isPresent();
        if (google) {
            base = workingHoursMinusGoogleBusy(userId, request);
        } else {
            base = availabilityRepository
                    .findByUserIdAndDateBetween(userId, request.dateFrom(), request.dateTo())
                    .stream()
                    .filter(a -> a.getStatus() == AvailabilityStatus.AVAILABLE)
                    .map(a -> timezoneService.toUtcRange(a.getDate(), a.getStartTime(), a.getEndTime(), a.getTimezone()))
                    .sorted(Comparator.comparing(TimezoneService.TimeRange::start))
                    .toList();
        }
        List<TimezoneService.TimeRange> existingRoundBusy = existingRoundBusyWindows(userId, round, request);
        List<TimezoneService.TimeRange> result = existingRoundBusy.isEmpty() ? base : subtractBusy(base, existingRoundBusy);
        if (google) {
            PERF_LOG.info("availableWindowsFor(google) userId={} took {}ms", userId, System.currentTimeMillis() - t0);
        }
        return result;
    }

    /**
     * This user's other SCHEDULED/IN_PROGRESS rounds within the search range, each expanded by
     * {@code round}'s own buffer minutes on both sides - one query per participant instead of
     * one per candidate start. Equivalent to what {@link ConflictDetectionService#checkConflicts}
     * computed per slot (a raw overlap or a buffer-only touch are both a conflict either way),
     * just computed once up front and subtracted like Google busy time instead of re-queried
     * for every candidate start. {@code round} itself is excluded (relevant when rescheduling).
     */
    private List<TimezoneService.TimeRange> existingRoundBusyWindows(UUID userId, InterviewRound round,
                                                                       SchedulingRequest request) {
        int bufferMinutes = round.getBufferMinutes();
        OffsetDateTime from = OffsetDateTime.of(request.dateFrom().minusDays(1).atStartOfDay(), ZoneOffset.UTC);
        OffsetDateTime to = OffsetDateTime.of(request.dateTo().plusDays(2).atStartOfDay(), ZoneOffset.UTC);
        return interviewRoundRepository.findScheduledOverlapsForUser(userId, from, to).stream()
                .filter(r -> !r.getId().equals(round.getId()))
                .map(r -> new TimezoneService.TimeRange(
                        r.getScheduledStart().toInstant().minus(bufferMinutes, ChronoUnit.MINUTES),
                        r.getScheduledEnd().toInstant().plus(bufferMinutes, ChronoUnit.MINUTES)))
                .toList();
    }

    /** Work-hours windows (one per eligible day in the request's date range, in this user's own
     * timezone, using their own {@code workingStart}/{@code workingEnd} override when they've
     * set one rather than the org-wide default) minus their Google Calendar busy time - see
     * {@link #availableWindowsFor}. */
    private List<TimezoneService.TimeRange> workingHoursMinusGoogleBusy(UUID userId, SchedulingRequest request) {
        User user = userRepository.findById(userId).orElse(null);
        String zoneId = user != null ? user.getTimezone() : "UTC";
        SchedulingConfig config = workingHoursService.currentConfig();
        LocalTime workingStart = user != null && user.getWorkingStart() != null
                ? user.getWorkingStart() : config.getWorkingStart();
        LocalTime workingEnd = user != null && user.getWorkingEnd() != null
                ? user.getWorkingEnd() : config.getWorkingEnd();
        List<TimezoneService.TimeRange> windows = new ArrayList<>();
        for (LocalDate date = request.dateFrom(); !date.isAfter(request.dateTo()); date = date.plusDays(1)) {
            if (workingHoursService.isWeekend(date) && !config.isAllowWeekends()) {
                continue;
            }
            windows.add(timezoneService.toUtcRange(date, workingStart, workingEnd, zoneId));
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
