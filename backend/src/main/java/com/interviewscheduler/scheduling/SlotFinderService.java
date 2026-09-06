package com.interviewscheduler.scheduling;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.availability.AvailabilityRepository;
import com.interviewscheduler.availability.AvailabilityStatus;
import com.interviewscheduler.availability.TimezoneService;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.candidate.CandidateStatus;
import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.RoundResult;
import com.interviewscheduler.interview.RoundStatus;
import com.interviewscheduler.interviewer.InterviewerMatchResult;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic feasibility search (spec steps 1-18). No AI, no ranking (step 19 is
 * {@link SlotRankingService}) - this only decides what is structurally bookable.
 */
@Service
@RequiredArgsConstructor
public class SlotFinderService {

    /** Candidate start times are tried at this stride within a common availability window. */
    private static final long STEP_MINUTES = 30;
    /** Caps how many candidate starts are tried per common window, to bound the search. */
    private static final int MAX_STARTS_PER_WINDOW = 6;

    private final CandidateRepository candidateRepository;
    private final InterviewRoundRepository interviewRoundRepository;
    private final UserRepository userRepository;
    private final AvailabilityRepository availabilityRepository;
    private final InterviewerMatchingService interviewerMatchingService;
    private final ConflictDetectionService conflictDetectionService;
    private final WorkingHoursService workingHoursService;
    private final TimezoneService timezoneService;

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
        validateSchedulable(candidate, round);

        List<InterviewerMatchResult> eligibleInterviewers =
                interviewerMatchingService.match(round.getId()).eligibleInterviewers();
        if (eligibleInterviewers.isEmpty()) {
            return SchedulingResponse.empty(SchedulingReasonCode.NO_QUALIFIED_INTERVIEWER);
        }

        List<TimezoneService.TimeRange> candidateWindows =
                availableWindowsFor(candidate.getUser().getId(), request);
        if (candidateWindows.isEmpty()) {
            return SchedulingResponse.empty(SchedulingReasonCode.NO_CANDIDATE_AVAILABILITY);
        }

        List<UUID> requiredParticipantIds = request.requiredParticipantIds() == null
                ? List.of() : request.requiredParticipantIds();
        List<CheckConflictsRequest.RequiredParticipant> requiredParticipants = requiredParticipantIds.stream()
                .map(this::resolveParticipant)
                .toList();

        List<TimezoneService.TimeRange> baseCommon = candidateWindows;
        for (UUID participantId : requiredParticipantIds) {
            baseCommon = intersect(baseCommon, availableWindowsFor(participantId, request));
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
            List<TimezoneService.TimeRange> interviewerWindows = availableWindowsFor(interviewer.userId(), request);
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

    private void validateSchedulable(Candidate candidate, InterviewRound round) {
        if (candidate.getCurrentStatus() == CandidateStatus.REJECTED
                || candidate.getCurrentStatus() == CandidateStatus.WITHDRAWN) {
            throw new ConflictException("Cannot schedule an interview for a rejected or withdrawn candidate");
        }
        if (round.getStatus() != RoundStatus.PENDING && round.getStatus() != RoundStatus.RESCHEDULE_REQUIRED) {
            throw new ConflictException("Round is not in a schedulable state: " + round.getStatus());
        }
        InterviewRound dependsOn = round.getDependsOnRound();
        if (dependsOn != null
                && !(dependsOn.getStatus() == RoundStatus.COMPLETED && dependsOn.getResult() == RoundResult.PASS)) {
            throw new ConflictException("Round " + round.getRoundNumber() + " depends on round "
                    + dependsOn.getRoundNumber() + ", which has not been completed with a PASS result");
        }
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

    private List<TimezoneService.TimeRange> availableWindowsFor(UUID userId, SchedulingRequest request) {
        return availabilityRepository.findByUserIdAndDateBetween(userId, request.dateFrom(), request.dateTo())
                .stream()
                .filter(a -> a.getStatus() == AvailabilityStatus.AVAILABLE)
                .map(a -> timezoneService.toUtcRange(a.getDate(), a.getStartTime(), a.getEndTime(), a.getTimezone()))
                .sorted(Comparator.comparing(TimezoneService.TimeRange::start))
                .toList();
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

    /**
     * No User.Role maps to ParticipantRole.HIRING_MANAGER (the schema has no such user role) -
     * a required participant is classified as RECRUITER unless their account role is literally
     * CANDIDATE or INTERVIEWER.
     */
    private CheckConflictsRequest.RequiredParticipant resolveParticipant(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + userId));
        ParticipantRole role = switch (user.getRole()) {
            case CANDIDATE -> ParticipantRole.CANDIDATE;
            case INTERVIEWER -> ParticipantRole.INTERVIEWER;
            case RECRUITER, ADMIN -> ParticipantRole.RECRUITER;
        };
        return new CheckConflictsRequest.RequiredParticipant(userId, role);
    }
}
