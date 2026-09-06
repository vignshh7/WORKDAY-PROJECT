package com.interviewscheduler.scheduling;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.availability.WorkingHoursService;
import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarEvent;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.integration.CalendarEventStatus;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.interview.RoundResult;
import com.interviewscheduler.interview.RoundStatus;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Deterministic, side-effect-free conflict check. This is deliberately the exact query
 * {@link InterviewRoundRepository#findScheduledOverlapsForUser} was built for — the spec
 * requires "a fresh conflict check immediately before booking," so Phase 12's booking flow
 * calls this same method again right before committing, not just at slot-finding time.
 */
@Service
@RequiredArgsConstructor
public class ConflictDetectionService {

    private static final Set<CalendarEventStatus> ACTIVE_CALENDAR_STATUSES =
            Set.of(CalendarEventStatus.PENDING, CalendarEventStatus.CREATED, CalendarEventStatus.UPDATED);

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewerProfileRepository interviewerProfileRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final WorkingHoursService workingHoursService;

    @Transactional(readOnly = true)
    public ConflictCheckResponse checkConflicts(CheckConflictsRequest request) {
        InterviewRound round = interviewRoundRepository.findById(request.roundId())
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + request.roundId()));

        List<ConflictResponse> conflicts = new ArrayList<>();

        if (!request.end().isAfter(request.start())) {
            conflicts.add(new ConflictResponse(ConflictType.INVALID_TIME_RANGE,
                    "End time must be after start time", null, request.start(), request.end()));
            return ConflictCheckResponse.of(conflicts);
        }

        SchedulingConfig config = workingHoursService.currentConfig();

        if (request.start().isBefore(OffsetDateTime.now().plusMinutes(config.getMinimumBookingNoticeMinutes()))) {
            conflicts.add(new ConflictResponse(ConflictType.NOTICE_PERIOD_CONFLICT,
                    "Proposed start is within the minimum booking notice period ("
                            + config.getMinimumBookingNoticeMinutes() + " minutes)",
                    null, request.start(), request.end()));
        }

        if (!workingHoursService.isWithinWorkingHours(request.start().toLocalTime(), request.end().toLocalTime())
                || (workingHoursService.isWeekend(request.start().toLocalDate()) && !workingHoursService.weekendsAllowed())) {
            conflicts.add(new ConflictResponse(ConflictType.WORKING_HOURS_CONFLICT,
                    "Proposed window falls outside working hours (" + config.getWorkingStart() + "-"
                            + config.getWorkingEnd() + ") or on a disallowed weekend",
                    null, request.start(), request.end()));
        }

        InterviewRound dependsOn = round.getDependsOnRound();
        if (dependsOn != null && !(dependsOn.getStatus() == RoundStatus.COMPLETED && dependsOn.getResult() == RoundResult.PASS)) {
            conflicts.add(new ConflictResponse(ConflictType.ROUND_DEPENDENCY_CONFLICT,
                    "Round " + round.getRoundNumber() + " depends on round " + dependsOn.getRoundNumber()
                            + ", which has not been completed with a PASS result",
                    dependsOn.getId(), dependsOn.getScheduledStart(), dependsOn.getScheduledEnd()));
        }

        int bufferMinutes = round.getBufferMinutes();
        Candidate candidate = round.getProcess().getCandidate();
        checkParticipant(candidate.getUser().getId(), ConflictType.CANDIDATE_CONFLICT,
                request, round.getId(), bufferMinutes, conflicts);

        if (request.interviewerId() != null) {
            InterviewerProfile interviewer = interviewerProfileRepository.findById(request.interviewerId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No interviewer with id: " + request.interviewerId()));
            checkParticipant(interviewer.getUser().getId(), ConflictType.INTERVIEWER_CONFLICT,
                    request, round.getId(), bufferMinutes, conflicts);
        }

        if (request.requiredParticipants() != null) {
            for (CheckConflictsRequest.RequiredParticipant participant : request.requiredParticipants()) {
                checkParticipant(participant.userId(), conflictTypeFor(participant.role()),
                        request, round.getId(), bufferMinutes, conflicts);
            }
        }

        return ConflictCheckResponse.of(conflicts);
    }

    /**
     * Queries once with the buffer-expanded window (covers raw overlaps and near-miss buffer
     * violations together), then classifies each hit: a real overlap against the *unexpanded*
     * request window is the participant's own conflict type; a hit found only because of the
     * buffer expansion (including two meetings that exactly touch, zero gap) is a buffer
     * conflict instead.
     */
    private void checkParticipant(UUID userId, ConflictType conflictType, CheckConflictsRequest request,
                                   UUID excludeRoundId, int bufferMinutes, List<ConflictResponse> conflicts) {
        List<InterviewRound> overlaps = interviewRoundRepository.findScheduledOverlapsForUser(
                userId, request.start().minusMinutes(bufferMinutes), request.end().plusMinutes(bufferMinutes));

        for (InterviewRound overlap : overlaps) {
            if (overlap.getId().equals(excludeRoundId)) {
                continue;
            }
            boolean rawOverlap = request.start().isBefore(overlap.getScheduledEnd())
                    && overlap.getScheduledStart().isBefore(request.end());

            if (rawOverlap) {
                conflicts.add(new ConflictResponse(conflictType,
                        "Overlaps an existing scheduled interview", overlap.getId(),
                        overlap.getScheduledStart(), overlap.getScheduledEnd()));
                checkCalendarConflict(overlap, conflicts);
            } else {
                conflicts.add(new ConflictResponse(ConflictType.BUFFER_CONFLICT,
                        "Within the required " + bufferMinutes + "-minute buffer of an existing scheduled interview",
                        overlap.getId(), overlap.getScheduledStart(), overlap.getScheduledEnd()));
            }
        }
    }

    private void checkCalendarConflict(InterviewRound overlap, List<ConflictResponse> conflicts) {
        for (CalendarEvent event : calendarEventRepository.findByInterviewRoundId(overlap.getId())) {
            if (ACTIVE_CALENDAR_STATUSES.contains(event.getStatus())) {
                conflicts.add(new ConflictResponse(ConflictType.CALENDAR_CONFLICT,
                        "A calendar event already exists for the conflicting interview",
                        event.getId(), event.getStartTime(), event.getEndTime()));
            }
        }
    }

    private static ConflictType conflictTypeFor(ParticipantRole role) {
        return switch (role) {
            case CANDIDATE -> ConflictType.CANDIDATE_CONFLICT;
            case INTERVIEWER -> ConflictType.INTERVIEWER_CONFLICT;
            case RECRUITER -> ConflictType.RECRUITER_CONFLICT;
            case HIRING_MANAGER -> ConflictType.HIRING_MANAGER_CONFLICT;
        };
    }
}
