package com.interviewscheduler.interview;

import com.interviewscheduler.candidate.CandidateResponse;

import java.util.List;

/**
 * Everything {@code InterviewDetail} (the frontend's shared per-round page) needs in one call:
 * the round itself, its process, every sibling round in that process (for the "process" side
 * panel), and the candidate. Exists because there is no other way to fetch a single round by
 * id — see {@link InterviewProcessService#getRoundDetail} for why this had to be a purpose-built
 * endpoint rather than composed client-side from staff-only/self-only list endpoints.
 */
public record InterviewRoundDetailResponse(
        InterviewRoundResponse round,
        InterviewProcessResponse process,
        List<InterviewRoundResponse> rounds,
        CandidateResponse candidate
) {
}
