package com.interviewscheduler.integration;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 21 — detects drift between what this system booked and what the external calendar now
 * shows, and triggers the reusable rescheduling engine when a future SCHEDULED interview
 * becomes invalid. Google's push notification carries no event payload, only "something on this
 * calendar changed" (see {@link CalendarWebhookService}), so this re-checks every tracked future
 * event rather than a specific one — a bounded, honest simplification for a system whose upcoming
 * interview count is small, not the incremental sync-token diffing a production-scale Calendar
 * integration would eventually want.
 *
 * <p>The actual per-event drift check and reschedule trigger live in
 * {@link SingleCalendarEventReconciler}, called here one event at a time so a rescheduling
 * failure on one round (e.g. it already hit {@code maximum_reschedules} - a legitimate business
 * rule, unrelated to this phase) can't abort reconciliation of every other round in the same
 * scan or fail Google's webhook call outright.
 *
 * <p><b>Also runs on a fixed schedule</b>, independent of the webhook: {@link
 * CalendarWebhookService}'s Javadoc documents that the {@code events.watch()} call that
 * registers a push-notification channel with Google was never built, so in practice Google
 * never calls {@code /webhook} at all - an interviewer or other attendee declining the invite
 * directly in their own Google Calendar (rather than through this app's own decline/cancel
 * actions) would otherwise never be noticed. Polling every few minutes instead of registering a
 * push channel means catching a decline within one poll interval rather than instantly, but
 * needs no channel-expiration/renewal lifecycle to maintain.
 */
@Service
@RequiredArgsConstructor
public class CalendarReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CalendarReconciliationService.class);

    private final CalendarEventRepository calendarEventRepository;
    private final SingleCalendarEventReconciler singleEventReconciler;
    private final AuditService auditService;

    @Value("${calendar.provider:noop}")
    private String activeCalendarProvider;

    /** Every 10 seconds - a no-op unless {@code CALENDAR_PROVIDER=google}, since a NoOp
     *  provider has nothing to drift from. */
    @Scheduled(fixedRate = 10_000)
    public void reconcileFutureEventsOnSchedule() {
        if (!"google".equalsIgnoreCase(activeCalendarProvider)) {
            return;
        }
        reconcileFutureEvents();
    }

    public void reconcileFutureEvents() {
        for (UUID calendarEventId : calendarEventRepository.findByStatus(CalendarEventStatus.CREATED)
                .stream().map(CalendarEvent::getId).toList()) {
            try {
                singleEventReconciler.reconcile(calendarEventId);
            } catch (RuntimeException e) {
                log.warn("Reconciliation failed for calendarEventId={}: {}", calendarEventId, e.getMessage());
                auditService.log(null, ActorType.SYSTEM, AuditAction.CALENDAR_SYNC_FAILED,
                        "CALENDAR_EVENT", calendarEventId, Map.of("error", String.valueOf(e.getMessage())));
            }
        }
    }
}
