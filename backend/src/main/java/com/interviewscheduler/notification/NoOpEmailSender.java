package com.interviewscheduler.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Default email backend — active when {@code notification.email.provider} is {@code noop} or
 * unset. Logs the message instead of sending it. Mirrors {@code NoOpCalendarProvider}'s role:
 * every notification still persists as {@link NotificationStatus#SENT} (there's no real
 * provider that could fail), so the rest of the system behaves identically with or without a
 * real email account configured.
 */
@Service
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "noop", matchIfMissing = true)
public class NoOpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info("[EMAIL NOOP] to={} subject=\"{}\" body=\"{}\"", toEmail, subject, body);
    }
}
