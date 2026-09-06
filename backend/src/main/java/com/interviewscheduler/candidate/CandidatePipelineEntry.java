package com.interviewscheduler.candidate;

import com.interviewscheduler.interview.InterviewProcessResponse;
import com.interviewscheduler.interview.InterviewRoundResponse;

import java.util.List;

// A candidate can have more than one interview_processes row over time (e.g. reapplying
// to a different job), so the pipeline view is a list of these, not a single object.
public record CandidatePipelineEntry(
        InterviewProcessResponse process,
        List<InterviewRoundResponse> rounds
) {
}
