package com.interviewscheduler.notification;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.interview.InterviewRound;
import com.interviewscheduler.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Single point of notification delivery (Phase 22) — replaces the six copy-pasted
 * "build a PENDING {@link Notification} row and never send it" blocks that Phases 12-17 each
 * had. Persists every notification, then attempts delivery on its channel; failure never
 * propagates (the spec: "failure of email/SMS must not roll back a successful booking") -
 * it's recorded as {@link NotificationStatus#FAILED} plus a {@code NOTIFICATION_FAILED} audit
 * row, and picked up again by {@link #retryRecentFailures()} (Phase 23).
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final AuditService auditService;

    /** Most system-generated notifications are IN_APP - this is the common-case overload. */
    @Transactional
    public Notification notify(User user, InterviewRound round, NotificationType type) {
        return notify(user, round, type, NotificationChannel.IN_APP);
    }

    @Transactional
    public Notification notify(User user, InterviewRound round, NotificationType type, NotificationChannel channel) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setInterviewRound(round);
        notification.setType(type);
        notification.setChannel(channel);
        notification.setStatus(NotificationStatus.PENDING);
        Notification saved = notificationRepository.saveAndFlush(notification);
        deliver(saved);
        return saved;
    }

    /** Re-attempts delivery for every FAILED notification from the last 24h (Phase 23). Older
     *  failures are left alone rather than retried forever against a permanently bad address. */
    @Scheduled(fixedRate = 600_000)
    @Transactional
    public void retryRecentFailures() {
        notificationRepository.findByStatusAndCreatedAtAfter(NotificationStatus.FAILED, OffsetDateTime.now().minusHours(24))
                .forEach(this::deliver);
    }

    private void deliver(Notification notification) {
        try {
            switch (notification.getChannel()) {
                case IN_APP -> { /* the DB row itself is the delivery; nothing external to do */ }
                case EMAIL -> emailSender.send(notification.getUser().getEmail(), subjectFor(notification), bodyFor(notification));
                case SMS -> smsSender.send(notification.getUser().getEmail(), bodyFor(notification)); // no phone on User - see SmsSender javadoc
            }
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.warn("Notification delivery failed: id={} channel={} type={}: {}",
                    notification.getId(), notification.getChannel(), notification.getType(), e.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            auditService.log(null, ActorType.SYSTEM, AuditAction.NOTIFICATION_FAILED,
                    "NOTIFICATION", notification.getId(), Map.of("error", String.valueOf(e.getMessage())));
        }
        notificationRepository.save(notification);
    }

    private String subjectFor(Notification notification) {
        return switch (notification.getType()) {
            case INTERVIEW_SCHEDULED -> "Interview scheduled";
            case INTERVIEW_RESCHEDULED -> "Interview rescheduled";
            case INTERVIEW_CANCELLED -> "Interview cancelled";
            case INTERVIEWER_CANCELLED -> "Interviewer cancelled - rescheduling required";
            case INTERVIEWER_REPLACED -> "Interviewer replaced";
            case INTERVIEW_INVITATION -> "Interview invitation";
            case INTERVIEW_INVITATION_DECLINED -> "Interview invitation declined";
            case INTERVIEW_REMINDER, INTERVIEW_REMINDER_24H, INTERVIEW_REMINDER_1H -> "Interview reminder";
        };
    }

    private String bodyFor(Notification notification) {
        InterviewRound round = notification.getInterviewRound();
        String subject = subjectFor(notification);
        if (round == null) {
            return subject;
        }
        String candidateName = round.getProcess().getCandidate().getUser().getName();
        String when = round.getScheduledStart() != null ? round.getScheduledStart().toString() : "a time to be confirmed";
        return subject + ": " + round.getRoundType() + " interview for " + candidateName + " at " + when + ".";
    }
}
