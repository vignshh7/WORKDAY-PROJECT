package com.interviewscheduler.notification;

import com.interviewscheduler.common.exception.NotificationException;

/**
 * Pluggable SMS backend (spec: "SmsNotificationService interface"). No real implementation is
 * wired up: {@code User} carries no phone number (only {@code Candidate} does — see
 * {@code candidates.phone}), and nothing in the system currently sends a
 * {@link NotificationChannel#SMS} notification, so there is no real send path to implement yet.
 * {@link NoOpSmsSender} is the only implementation; a real one (Twilio, etc.) plugs in here the
 * same way {@link SmtpEmailSender} plugs into {@link EmailSender}.
 */
public interface SmsSender {

    void send(String toPhoneNumber, String message) throws NotificationException;
}
