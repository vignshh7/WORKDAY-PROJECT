package com.interviewscheduler.ai;

import com.interviewscheduler.admin.ReplacementPolicy;
import com.interviewscheduler.availability.WorkingHoursService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 26/27 — natural-language scheduling orchestration: "LLM -> Controlled Tools -> Spring
 * Boot Services -> deterministic validation" (the master spec's own design statement), not
 * "LLM -> Database -> Booking". The model autonomously calls read-only tools
 * ({@link AiReadOnlyTools}) to gather everything it needs, then produces the structured
 * {@link AiScheduleResponse} Phase 27 specifies. It never performs a mutation itself: a
 * non-null {@link ProposedAction} is only ever actually executed by {@link AiActionExecutor},
 * triggered by a separate, explicit recruiter confirmation call - unless the configured
 * interviewer-replacement policy is {@link ReplacementPolicy#AUTO_SWITCH_IF_QUALIFIED}, in
 * which case a proposed {@code SWITCH_INTERVIEWER} is executed immediately, the one exception
 * Phase 27 itself calls out ("require explicit recruiter confirmation unless configured for
 * AUTO_SWITCH_IF_QUALIFIED").
 */
@Service
public class AiOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestrationService.class);

    private static final String SYSTEM_PROMPT = """
            You are a scheduling assistant for an interview scheduling platform. You help a
            recruiter schedule, reschedule, or cancel interviews, switch interviewers, and
            advance or reject candidates, using only the tools provided - never from your own
            knowledge or assumptions.

            Rules you must follow exactly:
            - Every fact in your response (candidate id/name, round id, interviewer ids, slot
              times, scores, ineligibility reasons) MUST come from a tool call result you
              actually received in this conversation. Never invent or guess an id, name, date,
              or time - not even a plausible-looking one.
            - If you cannot find a candidate, round, or slot, say so plainly in `reason` and
              leave the corresponding field null/empty rather than fabricate one.
            - To find a candidate, call findCandidate with a name/email fragment from the
              request, then getInterviewProcess/getCurrentRound for their active round.
            - To find slots, prefer recommendSlots (it also ranks the results) over
              findCommonSlots, unless the user explicitly wants the raw unranked list.
            - You never book, reschedule, cancel, switch an interviewer, advance a candidate, or
              reject a candidate yourself - you only ever PROPOSE one such action, by populating
              the `action` field and setting confirmationRequired=true. A human recruiter
              confirms separately before anything is actually changed. Only ever propose at most
              one action per response.
            - For a purely informational question (e.g. "what is Rahul's status"), leave
              `action` null and `confirmationRequired` false.
            - Respond only with the requested structured JSON - no commentary outside it.
            """;

    private final ChatClient chatClient;
    private final AiReadOnlyTools tools;
    private final WorkingHoursService workingHoursService;
    private final AiActionExecutor actionExecutor;

    public AiOrchestrationService(ChatClient.Builder chatClientBuilder, AiReadOnlyTools tools,
                                   WorkingHoursService workingHoursService, AiActionExecutor actionExecutor) {
        this.chatClient = chatClientBuilder.build();
        this.tools = tools;
        this.workingHoursService = workingHoursService;
        this.actionExecutor = actionExecutor;
    }

    public AiScheduleResponse interpret(String message) {
        AiScheduleResponse response = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .tools(tools)
                .call()
                .entity(AiScheduleResponse.class);

        if (response == null) {
            log.warn("AI model returned no structured response for message: {}", message);
            return new AiScheduleResponse(message, null, null, List.of(), List.of(), List.of(),
                    "The AI model returned no structured response.", false, null);
        }

        if (response.action() != null && response.action().type() == AiActionType.SWITCH_INTERVIEWER
                && workingHoursService.currentConfig().getInterviewerReplacementPolicy() == ReplacementPolicy.AUTO_SWITCH_IF_QUALIFIED) {
            return autoExecuteSwitch(response);
        }

        return response;
    }

    private AiScheduleResponse autoExecuteSwitch(AiScheduleResponse response) {
        try {
            Object result = actionExecutor.execute(response.action(), UUID.randomUUID().toString());
            return new AiScheduleResponse(response.interpretedRequest(), response.candidate(), response.round(),
                    response.eligibleInterviewers(), response.recommendedSlots(), response.alternatives(),
                    response.reason() + " Interviewer switched automatically (AUTO_SWITCH_IF_QUALIFIED policy): "
                            + result, false, null);
        } catch (RuntimeException e) {
            log.warn("Auto-switch failed for proposed action {}: {}", response.action(), e.getMessage());
            return new AiScheduleResponse(response.interpretedRequest(), response.candidate(), response.round(),
                    response.eligibleInterviewers(), response.recommendedSlots(), response.alternatives(),
                    response.reason() + " Automatic interviewer switch failed: " + e.getMessage(),
                    true, response.action());
        }
    }
}
