package com.interviewscheduler.notification;

import com.interviewscheduler.common.exception.NotificationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Real email backend — active when {@code notification.email.provider=smtp}. Uses Spring's
 * {@link JavaMailSender} (SMTP) rather than a specific vendor SDK, since the spec only names two
 * env vars ({@code EMAIL_API_KEY}, {@code EMAIL_FROM}) without naming a provider: most
 * transactional email providers (SendGrid, Mailgun, Postmark, etc.) expose an SMTP relay where
 * an API key doubles as the SMTP password, so this works against any of them by pointing
 * Spring's standard {@code spring.mail.*} properties (host/port/username/password - set via the
 * matching {@code SPRING_MAIL_*} env vars) at that provider's relay, with {@code EMAIL_API_KEY}
 * as a natural value for {@code SPRING_MAIL_PASSWORD}. No provider-specific dependency needed.
 * {@code EMAIL_FROM} is used here only for the message's From header, independent of the SMTP
 * login credentials.
 */
@Service
@ConditionalOnProperty(name = "notification.email.provider", havingValue = "smtp")
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;

    @Value("${EMAIL_FROM:}")
    private String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void send(String toEmail, String subject, String body) {
        if (fromAddress.isBlank()) {
            throw new NotificationException("EMAIL_FROM is not configured - required when notification.email.provider=smtp");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
        } catch (MailException e) {
            throw new NotificationException("Failed to send email to " + toEmail + ": " + e.getMessage(), e);
        }
    }
}
