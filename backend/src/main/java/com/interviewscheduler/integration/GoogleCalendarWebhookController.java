package com.interviewscheduler.integration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 21. Public (see {@code SecurityConfig}) — Google calls this directly with no JWT.
 * Google requires a bare 2xx response with no particular body, and retries with backoff on
 * anything else, so any error here is swallowed by {@link CalendarWebhookService} rather than
 * surfaced as a non-2xx (which would just cause Google to keep retrying the same notification).
 */
@RestController
@RequestMapping("/api/integrations/google-calendar")
@RequiredArgsConstructor
public class GoogleCalendarWebhookController {

    private final CalendarWebhookService webhookService;

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestHeader("X-Goog-Channel-Id") String channelId,
            @RequestHeader("X-Goog-Resource-Id") String resourceId,
            @RequestHeader("X-Goog-Resource-State") String resourceState,
            @RequestHeader(value = "X-Goog-Message-Number", defaultValue = "0") long messageNumber,
            @RequestHeader(value = "X-Goog-Channel-Token", required = false) String channelToken) {
        webhookService.handle(channelId, resourceId, resourceState, messageNumber, channelToken);
        return ResponseEntity.ok().build();
    }
}
