package com.interviewscheduler.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserId(UUID userId);

    List<Notification> findByStatus(NotificationStatus status);

    List<Notification> findByStatusAndCreatedAtAfter(NotificationStatus status, OffsetDateTime after);

    List<Notification> findByInterviewRoundId(UUID interviewRoundId);

    boolean existsByUserIdAndInterviewRoundIdAndType(UUID userId, UUID interviewRoundId, NotificationType type);

    /** Phase 23: "on reschedule/cancellation, invalidate old reminders" - deletes stale reminder
     *  rows for a round so the dedup check in {@code existsByUserIdAndInterviewRoundIdAndType}
     *  doesn't block a fresh reminder once the round is rescheduled to a new time. */
    @Modifying
    @Transactional
    void deleteByInterviewRoundIdAndTypeIn(UUID interviewRoundId, List<NotificationType> types);
}
