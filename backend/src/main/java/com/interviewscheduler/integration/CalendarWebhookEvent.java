package com.interviewscheduler.integration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "calendar_webhook_events")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class CalendarWebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "channel_id", nullable = false, length = 200)
    private String channelId;

    @Column(name = "resource_id", nullable = false, length = 200)
    private String resourceId;

    @Column(name = "message_number", nullable = false)
    private long messageNumber;

    @Column(name = "resource_state", nullable = false, length = 50)
    private String resourceState;

    @CreationTimestamp
    @Column(name = "processed_at", nullable = false, updatable = false)
    private OffsetDateTime processedAt;
}
