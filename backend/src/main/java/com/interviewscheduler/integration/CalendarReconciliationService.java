package com.interviewscheduler.integration;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
@RequiredArgsConstructor
public class CalendarReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(CalendarReconciliationService.class);

    private final CalendarEventRepository calendarEventRepository;
    private final SingleCalendarEventReconciler singleEventReconciler;
    private final AuditService auditService;

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
