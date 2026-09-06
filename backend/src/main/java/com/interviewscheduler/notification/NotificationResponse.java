package com.interviewscheduler.notification;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID userId,
        UUID interviewRoundId,
        NotificationType type,
        NotificationChannel channel,
        NotificationStatus status,
        OffsetDateTime sentAt,
        OffsetDateTime createdAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUser().getId(),
                notification.getInterviewRound() == null ? null : notification.getInterviewRound().getId(),
                notification.getType(),
                notification.getChannel(),
                notification.getStatus(),
                notification.getSentAt(),
                notification.getCreatedAt()
        );
    }
}
