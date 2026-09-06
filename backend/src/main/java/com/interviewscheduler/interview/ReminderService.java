package com.interviewscheduler.interview;

import com.interviewscheduler.notification.NotificationRepository;
import com.interviewscheduler.notification.NotificationService;
import com.interviewscheduler.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase 23 — 24h and 1h reminders before a SCHEDULED interview. Runs as a periodic scan rather
 * than scheduling a one-shot timer per booking: simpler, survives app restarts without needing
 * persisted timer state, and self-corrects if a round's time changes between scans.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final List<NotificationType> REMINDER_TYPES =
            List.of(NotificationType.INTERVIEW_REMINDER_24H, NotificationType.INTERVIEW_REMINDER_1H);

    /** Scan runs every 5 minutes; the match window is the same width so no due reminder is missed. */
    private static final long WINDOW_MINUTES = 5;

    private final InterviewRoundRepository interviewRoundRepository;
    private final InterviewParticipantRepository interviewParticipantRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void sendDueReminders() {
        OffsetDateTime now = OffsetDateTime.now();
        sendFor(now.plusHours(24), NotificationType.INTERVIEW_REMINDER_24H);
        sendFor(now.plusHours(1), NotificationType.INTERVIEW_REMINDER_1H);
    }

    /**
     * "On reschedule: invalidate old reminders and create new ones. On cancellation: cancel
     * reminders." — deletes any reminder rows already recorded for this round so the dedup
     * check in {@link #sendFor} doesn't block a fresh reminder once it's rescheduled to a new
     * time (a round keeps the same id across a reschedule, so without this the old rows would
     * permanently block resending). Safe to call unconditionally from both cancel and
     * reschedule flows.
     */
    @Transactional
    public void invalidateReminders(UUID roundId) {
        notificationRepository.deleteByInterviewRoundIdAndTypeIn(roundId, REMINDER_TYPES);
    }

    private void sendFor(OffsetDateTime targetTime, NotificationType type) {
        OffsetDateTime windowStart = targetTime.minusMinutes(WINDOW_MINUTES);
        OffsetDateTime windowEnd = targetTime.plusMinutes(WINDOW_MINUTES);
        for (InterviewRound round : interviewRoundRepository
                .findByStatusAndScheduledStartBetween(RoundStatus.SCHEDULED, windowStart, windowEnd)) {
            interviewParticipantRepository.findByInterviewRoundId(round.getId()).stream()
                    .filter(p -> p.getStatus() != ParticipantStatus.REMOVED && p.getStatus() != ParticipantStatus.DECLINED)
                    .map(InterviewParticipant::getUser)
                    .distinct()
                    .forEach(user -> {
                        if (!notificationRepository.existsByUserIdAndInterviewRoundIdAndType(user.getId(), round.getId(), type)) {
                            notificationService.notify(user, round, type);
                        }
                    });
        }
    }
}
