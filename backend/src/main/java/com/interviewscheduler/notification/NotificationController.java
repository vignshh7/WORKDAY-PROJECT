package com.interviewscheduler.notification;

import com.interviewscheduler.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * Lets a user see their own notifications - self-only, no staff override, since a notification
 * is inherently personal (unlike e.g. a candidate profile, where a recruiter legitimately needs
 * read access). This was a real gap until now: {@link NotificationResponse} has existed since
 * Phase 4, and every Phase 12-23 flow has been writing {@link Notification} rows all along, but
 * nothing ever exposed them - found while preparing the backend for frontend integration.
 *
 * <p>No read/unread tracking exists in the schema ({@link NotificationStatus} is
 * PENDING/SENT/FAILED - delivery state, not read state) - a notification center built on this
 * can list every notification newest-first, but an unread badge count needs a schema change
 * (e.g. an {@code is_read} column) that hasn't been made; not invented here since it wasn't
 * asked for and there's no obvious default (e.g. does listing mark them all read? per-item?).
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notificationRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificationResponse> myNotifications() {
        return notificationRepository.findByUserId(SecurityUtils.currentUser().getId()).stream()
                .sorted(Comparator.comparing(Notification::getCreatedAt).reversed())
                .map(NotificationResponse::from)
                .toList();
    }
}
