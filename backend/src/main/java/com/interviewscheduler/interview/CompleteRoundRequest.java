package com.interviewscheduler.interview;

// Marks a SCHEDULED/IN_PROGRESS round as COMPLETED (the interview took place). Intentionally
// empty: all context comes from the path variable and the authenticated caller. The separate
// RoundResultRequest then records PASS/FAIL/HOLD, which is what actually drives pipeline
// progression — see Phase 7's distinction between round status and round result.
public record CompleteRoundRequest() {
}
