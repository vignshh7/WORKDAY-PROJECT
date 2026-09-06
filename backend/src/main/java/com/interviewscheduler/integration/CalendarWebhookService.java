package com.interviewscheduler.integration;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles Google Calendar push notifications (see
 * <a href="https://developers.google.com/calendar/api/guides/push">Push Notifications</a>).
 * Google's payload carries no event data — only headers saying "channel X changed to state Y" —
 * so this only needs to (1) verify the request is genuinely from the channel we created,
 * (2) dedupe against redelivery/loops, and (3) kick off {@link CalendarReconciliationService}.
 *
 * <p><b>Not built here:</b> the {@code events.watch()} call that registers a channel in the
 * first place (with its expiration/renewal lifecycle) — Phase 21 only asks for the receiving
 * end and the reconciliation behavior once a notification arrives, not channel management.
 */
@Service
@RequiredArgsConstructor
public class CalendarWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CalendarWebhookService.class);

    /** Shared secret set as the channel's token when the watch channel is created; verified on
     *  every delivery. Blank (default) skips verification - only safe for local/demo use. */
    @Value("${GOOGLE_CALENDAR_WEBHOOK_TOKEN:}")
    private String expectedChannelToken;

    private final CalendarWebhookEventRepository webhookEventRepository;
    private final CalendarReconciliationService reconciliationService;

    @Transactional
    public void handle(String channelId, String resourceId, String resourceState, long messageNumber, String channelToken) {
        if (!expectedChannelToken.isBlank() && !expectedChannelToken.equals(channelToken)) {
            log.warn("Rejected Google Calendar webhook with mismatched channel token, channelId={}", channelId);
            return;
        }
        if (webhookEventRepository.existsByChannelIdAndMessageNumber(channelId, messageNumber)) {
            log.debug("Ignoring duplicate webhook delivery channelId={} messageNumber={}", channelId, messageNumber);
            return;
        }

        CalendarWebhookEvent event = new CalendarWebhookEvent();
        event.setChannelId(channelId);
        event.setResourceId(resourceId);
        event.setMessageNumber(messageNumber);
        event.setResourceState(resourceState);
        webhookEventRepository.save(event);

        if ("sync".equals(resourceState)) {
            // Initial handshake sent once when the watch channel is created - no actual change.
            return;
        }

        log.info("Google Calendar webhook received: channelId={} resourceState={} - reconciling", channelId, resourceState);
        reconciliationService.reconcileFutureEvents();
    }
}
