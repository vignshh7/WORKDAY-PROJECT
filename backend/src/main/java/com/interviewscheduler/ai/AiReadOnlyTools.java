package com.interviewscheduler.ai;

import com.interviewscheduler.candidate.CandidatePipelineEntry;
import com.interviewscheduler.candidate.CandidateResponse;
import com.interviewscheduler.candidate.CandidateService;
import com.interviewscheduler.candidate.CandidateSkillResponse;
import com.interviewscheduler.candidate.CandidateStatusResponse;
import com.interviewscheduler.availability.AvailabilityResponse;
import com.interviewscheduler.availability.CandidateAvailabilityService;
import com.interviewscheduler.availability.InterviewerAvailabilityService;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.integration.CalendarEventRepository;
import com.interviewscheduler.interview.InterviewProcessResponse;
import com.interviewscheduler.interview.InterviewProcessService;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.interview.InterviewRoundRepository;
import com.interviewscheduler.interview.InterviewRoundResponse;
import com.interviewscheduler.interview.RoundRequirement;
import com.interviewscheduler.interview.RoundRequirementRepository;
import com.interviewscheduler.interviewer.InterviewerMatchingService;
import com.interviewscheduler.interviewer.InterviewerResponse;
import com.interviewscheduler.interviewer.InterviewerService;
import com.interviewscheduler.interviewer.InterviewerSkillResponse;
import com.interviewscheduler.interviewer.MatchInterviewersResponse;
import com.interviewscheduler.job.JobResponse;
import com.interviewscheduler.job.JobService;
import com.interviewscheduler.job.JobSkillResponse;
import com.interviewscheduler.scheduling.CheckConflictsRequest;
import com.interviewscheduler.scheduling.ConflictCheckResponse;
import com.interviewscheduler.scheduling.ConflictDetectionService;
import com.interviewscheduler.scheduling.FindReplacementResponse;
import com.interviewscheduler.scheduling.InterviewerReplacementService;
import com.interviewscheduler.scheduling.SchedulingRequest;
import com.interviewscheduler.scheduling.SchedulingResponse;
import com.interviewscheduler.scheduling.SchedulingService;
import com.interviewscheduler.scheduling.SlotFinderService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase 26 — read-only tools available to the AI agent, auto-executed during
 * {@code POST /api/ai/schedule} (Phase 27: "read-only tool calls can execute automatically").
 * Every method is a thin wrapper over an already-existing, already-tested service or repository
 * — the AI layer never touches the database directly, never invents availability/skills/facts,
 * and never bypasses RBAC (each wrapped service still runs its own object-level authorization
 * against {@code SecurityUtils.currentUser()}, which resolves to whichever recruiter's JWT
 * authenticated the {@code /api/ai/schedule} request).
 *
 * <p>Two spec tools are deliberately folded into others rather than exposed 1:1, documented
 * here rather than silently dropped:
 * <ul>
 *   <li>{@code rankSlots()} — ranking only makes sense given an already-found slot list, which
 *   is far more reliably obtained by calling {@link #recommendSlots} directly than by asking
 *   the model to round-trip a large structured slot list between two tool calls. {@code findCommonSlots}
 *   (raw, unranked - Phase 11 steps 1-18) and {@code recommendSlots} (find + rank, steps 1-19,
 *   the same pipeline {@code POST /api/scheduling/recommend} uses) together cover both spec
 *   tools' actual purpose.</li>
 *   <li>{@code findCandidate()} searches the existing {@code CandidateService#findAll()} list
 *   in memory rather than adding a new indexed search query - acceptable at this system's scale
 *   and avoids inventing new DB access surface for a tool that's or a lookup convenience.</li>
 * </ul>
 */
@Component
@Transactional(readOnly = true)
public class AiReadOnlyTools {

    private final CandidateService candidateService;
    private final JobService jobService;
    private final InterviewProcessService interviewProcessService;
    private final InterviewRoundRepository interviewRoundRepository;
    private final RoundRequirementRepository roundRequirementRepository;
    private final InterviewerService interviewerService;
    private final InterviewerMatchingService interviewerMatchingService;
    private final CandidateAvailabilityService candidateAvailabilityService;
    private final InterviewerAvailabilityService interviewerAvailabilityService;
    private final CalendarEventRepository calendarEventRepository;
    private final SlotFinderService slotFinderService;
    private final SchedulingService schedulingService;
    private final ConflictDetectionService conflictDetectionService;
    private final InterviewerReplacementService interviewerReplacementService;

    public AiReadOnlyTools(CandidateService candidateService, JobService jobService,
                            InterviewProcessService interviewProcessService,
                            InterviewRoundRepository interviewRoundRepository,
                            RoundRequirementRepository roundRequirementRepository,
                            InterviewerService interviewerService,
                            InterviewerMatchingService interviewerMatchingService,
                            CandidateAvailabilityService candidateAvailabilityService,
                            InterviewerAvailabilityService interviewerAvailabilityService,
                            CalendarEventRepository calendarEventRepository,
                            SlotFinderService slotFinderService,
                            SchedulingService schedulingService,
                            ConflictDetectionService conflictDetectionService,
                            InterviewerReplacementService interviewerReplacementService) {
        this.candidateService = candidateService;
        this.jobService = jobService;
        this.interviewProcessService = interviewProcessService;
        this.interviewRoundRepository = interviewRoundRepository;
        this.roundRequirementRepository = roundRequirementRepository;
        this.interviewerService = interviewerService;
        this.interviewerMatchingService = interviewerMatchingService;
        this.candidateAvailabilityService = candidateAvailabilityService;
        this.interviewerAvailabilityService = interviewerAvailabilityService;
        this.calendarEventRepository = calendarEventRepository;
        this.slotFinderService = slotFinderService;
        this.schedulingService = schedulingService;
        this.conflictDetectionService = conflictDetectionService;
        this.interviewerReplacementService = interviewerReplacementService;
    }

    @Tool(description = "Find candidates whose name or email contains the given text (case-insensitive). "
            + "Returns id, name, email, current pipeline status and current round id for each match.")
    public List<CandidateResponse> findCandidate(@ToolParam(description = "Name or email substring to search for") String query) {
        String needle = query == null ? "" : query.toLowerCase();
        return candidateService.findAll().stream()
                .filter(c -> c.name().toLowerCase().contains(needle) || c.email().toLowerCase().contains(needle))
                .limit(10)
                .toList();
    }

    @Tool(description = "Get a candidate's skills with proficiency and years of experience")
    public List<CandidateSkillResponse> getCandidateSkills(@ToolParam(description = "Candidate id") UUID candidateId) {
        return candidateService.getSkills(candidateId);
    }

    @Tool(description = "Get a candidate's current pipeline status (e.g. SCREENING, TECHNICAL, SELECTED, REJECTED)")
    public CandidateStatusResponse getCandidateStatus(@ToolParam(description = "Candidate id") UUID candidateId) {
        return candidateService.getStatus(candidateId);
    }

    @Tool(description = "Get a candidate's full interview pipeline: every interview process and its rounds with their status/result")
    public List<CandidatePipelineEntry> getCandidatePipeline(@ToolParam(description = "Candidate id") UUID candidateId) {
        return candidateService.getPipeline(candidateId);
    }

    @Tool(description = "Get a job posting's details")
    public JobResponse getJob(@ToolParam(description = "Job id") UUID jobId) {
        return jobService.findById(jobId);
    }

    @Tool(description = "Get a job's required skills with minimum proficiency and weight")
    public List<JobSkillResponse> getJobSkills(@ToolParam(description = "Job id") UUID jobId) {
        return jobService.getSkills(jobId);
    }

    @Tool(description = "Get a candidate's active interview process (which job they're being considered for, current round number, process status)")
    public InterviewProcessResponse getInterviewProcess(@ToolParam(description = "Candidate id") UUID candidateId) {
        return interviewProcessService.findActiveByCandidate(candidateId);
    }

    @Tool(description = "Get the interview round the candidate is currently on (round type, status, scheduled time if any)")
    public InterviewRoundResponse getCurrentRound(@ToolParam(description = "Candidate id") UUID candidateId) {
        InterviewProcessResponse process = interviewProcessService.findActiveByCandidate(candidateId);
        InterviewRound round = interviewRoundRepository
                .findByProcessIdAndRoundNumber(process.id(), process.currentRound())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No round " + process.currentRound() + " for process " + process.id()));
        return InterviewRoundResponse.from(round);
    }

    @Tool(description = "Get the required skills and minimum proficiency for a specific interview round "
            + "(falls back to the job's own required skills if the round has none of its own)")
    public List<RoundRequirementSummary> getRoundRequirements(@ToolParam(description = "Interview round id") UUID roundId) {
        return roundRequirementRepository.findByRoundId(roundId).stream()
                .map(r -> new RoundRequirementSummary(r.getSkill().getName(), r.getMinimumProficiency(), r.isRequired()))
                .toList();
    }

    public record RoundRequirementSummary(String skillName, Short minimumProficiency, boolean required) {
    }

    @Tool(description = "Get an interview round's current status, result, and scheduled time")
    public InterviewRoundResponse getInterviewStatus(@ToolParam(description = "Interview round id") UUID roundId) {
        InterviewRound round = interviewRoundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("No interview round with id: " + roundId));
        return InterviewRoundResponse.from(round);
    }

    @Tool(description = "List all interviewer profiles (id, name, department, domain)")
    public List<InterviewerResponse> findInterviewers() {
        return interviewerService.findAll();
    }

    @Tool(description = "Get an interviewer's skills with proficiency, years of experience, and whether each is a primary skill")
    public List<InterviewerSkillResponse> getInterviewerSkills(@ToolParam(description = "Interviewer profile id") UUID interviewerId) {
        return interviewerService.getSkills(interviewerId);
    }

    @Tool(description = "Find and rank interviewers eligible for a round by required skills, proficiency, domain, and workload. "
            + "Returns eligible interviewers (ranked) and ineligible ones with the reason they were excluded.")
    public MatchInterviewersResponse matchInterviewers(@ToolParam(description = "Interview round id") UUID roundId) {
        return interviewerMatchingService.match(roundId);
    }

    @Tool(description = "Get a candidate's own recorded availability windows between two dates (does not include any external calendar)")
    public List<AvailabilityResponse> getCandidateAvailability(
            @ToolParam(description = "Candidate profile id") UUID candidateId,
            @ToolParam(description = "Start date, inclusive, ISO-8601 (yyyy-MM-dd)") LocalDate from,
            @ToolParam(description = "End date, inclusive, ISO-8601 (yyyy-MM-dd)") LocalDate to) {
        return candidateAvailabilityService.getAvailability(candidateId, from, to);
    }

    @Tool(description = "Get an interviewer's own recorded availability windows between two dates (does not include any external calendar)")
    public List<AvailabilityResponse> getInterviewerAvailability(
            @ToolParam(description = "Interviewer profile id") UUID interviewerId,
            @ToolParam(description = "Start date, inclusive, ISO-8601 (yyyy-MM-dd)") LocalDate from,
            @ToolParam(description = "End date, inclusive, ISO-8601 (yyyy-MM-dd)") LocalDate to) {
        return interviewerAvailabilityService.getAvailability(interviewerId, from, to);
    }

    @Tool(description = "Get the calendar event(s) recorded for an interview round: status, provider, meeting link, and scheduled time")
    public List<CalendarEventSummary> getCalendarEvents(@ToolParam(description = "Interview round id") UUID roundId) {
        return calendarEventRepository.findByInterviewRoundId(roundId).stream()
                .map(e -> new CalendarEventSummary(e.getStatus().name(), e.getProvider(), e.getMeetingLink(),
                        e.getStartTime(), e.getEndTime()))
                .toList();
    }

    public record CalendarEventSummary(String status, String provider, String meetingLink,
                                        OffsetDateTime start, OffsetDateTime end) {
    }

    @Tool(description = "Deterministically find feasible (unranked) interview slots for a round, given a candidate, "
            + "duration, date range and optional preferences. Combines the candidate's and each eligible interviewer's "
            + "own recorded availability plus their connected Google Calendar, working hours, timezone, buffer and "
            + "conflicts. Never invents a slot - every slot returned already passed a real conflict check.")
    public SchedulingResponse findCommonSlots(@ToolParam(description = "Slot search parameters") SchedulingRequest request) {
        return slotFinderService.findSlots(request);
    }

    @Tool(description = "Find and rank the best interview slots for a round (same parameters as findCommonSlots, "
            + "but also scored/sorted by interviewer match quality, preferred time, and how soon the slot is). "
            + "This is the primary tool for answering 'when can we schedule X's interview' - prefer it over findCommonSlots "
            + "unless the raw unranked list is specifically needed.")
    public SchedulingResponse recommendSlots(@ToolParam(description = "Slot search parameters") SchedulingRequest request) {
        return schedulingService.recommend(request);
    }

    @Tool(description = "Fresh check for scheduling conflicts (double-booking, buffer violations, working hours, "
            + "notice period, round dependency) for a specific proposed time window")
    public ConflictCheckResponse checkConflicts(
            @ToolParam(description = "Interview round id") UUID roundId,
            @ToolParam(description = "Proposed start time, ISO-8601 offset date-time") OffsetDateTime start,
            @ToolParam(description = "Proposed end time, ISO-8601 offset date-time") OffsetDateTime end,
            @ToolParam(description = "Interviewer profile id being checked, if any", required = false) UUID interviewerId) {
        return conflictDetectionService.checkConflicts(new CheckConflictsRequest(roundId, start, end, interviewerId, List.of()));
    }

    @Tool(description = "Search for a qualified replacement interviewer for a round whose current interviewer cancelled "
            + "or declined - checks skills, proficiency, domain, availability and workload. Read-only: does not "
            + "actually switch the interviewer (use the switch_interviewer action for that, which requires confirmation).")
    public FindReplacementResponse findReplacementInterviewer(@ToolParam(description = "Interview round id") UUID roundId) {
        return interviewerReplacementService.findReplacement(roundId);
    }
}
