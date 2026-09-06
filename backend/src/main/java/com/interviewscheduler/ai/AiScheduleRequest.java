package com.interviewscheduler.ai;

import jakarta.validation.constraints.NotBlank;

/** A natural-language scheduling request, e.g. "Schedule Rahul's Java technical interview
 *  next week for 60 minutes, prefer afternoon, exclude Friday." */
public record AiScheduleRequest(@NotBlank String message) {
}
