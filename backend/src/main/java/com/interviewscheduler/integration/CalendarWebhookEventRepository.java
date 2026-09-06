package com.interviewscheduler.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CalendarWebhookEventRepository extends JpaRepository<CalendarWebhookEvent, UUID> {

    boolean existsByChannelIdAndMessageNumber(String channelId, long messageNumber);
}
