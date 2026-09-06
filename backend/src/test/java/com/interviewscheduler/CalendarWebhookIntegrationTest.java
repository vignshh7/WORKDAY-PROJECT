package com.interviewscheduler;

import com.interviewscheduler.integration.CalendarWebhookEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 — CALENDAR: webhook (public, dedup, sync handshake no-op). */
class CalendarWebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CalendarWebhookEventRepository webhookEventRepository;

    private ResponseEntity<String> callWebhook(String channelId, String resourceState, long messageNumber) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Goog-Channel-Id", channelId);
        headers.set("X-Goog-Resource-Id", "res-" + channelId);
        headers.set("X-Goog-Resource-State", resourceState);
        headers.set("X-Goog-Message-Number", String.valueOf(messageNumber));
        return restTemplate.exchange(url("/api/integrations/google-calendar/webhook"),
                org.springframework.http.HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    @Test
    void webhook_isPublic_noAuthorizationHeaderNeeded() {
        String channelId = "phase28-" + UUID.randomUUID();
        ResponseEntity<String> resp = callWebhook(channelId, "sync", 1);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void webhook_duplicateMessageNumber_isDedupedNotDoubleProcessed() {
        String channelId = "phase28-dedup-" + UUID.randomUUID();
        assertThat(callWebhook(channelId, "exists", 1).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(callWebhook(channelId, "exists", 1).getStatusCode()).isEqualTo(HttpStatus.OK);

        long recorded = webhookEventRepository.findAll().stream()
                .filter(e -> e.getChannelId().equals(channelId) && e.getMessageNumber() == 1)
                .count();
        assertThat(recorded).isEqualTo(1);
    }
}
