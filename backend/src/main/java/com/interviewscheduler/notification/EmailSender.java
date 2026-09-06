package com.interviewscheduler.notification;

import com.interviewscheduler.common.exception.NotificationException;

/** Pluggable email backend. Exactly one implementation is active (see {@code notification.email.provider}). */
public interface EmailSender {

    void send(String toEmail, String subject, String body) throws NotificationException;
}
