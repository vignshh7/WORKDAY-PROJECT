package com.interviewscheduler.ai;

public record AiConfirmResponse(
        boolean success,
        AiActionType actionType,
        Object result,
        String message
) {
}
