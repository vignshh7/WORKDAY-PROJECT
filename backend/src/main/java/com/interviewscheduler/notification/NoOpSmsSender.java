package com.interviewscheduler.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Only {@link SmsSender} implementation for now — see its javadoc for why. */
@Service
public class NoOpSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(NoOpSmsSender.class);

    @Override
    public void send(String toPhoneNumber, String message) {
        log.info("[SMS NOOP] to={} message=\"{}\"", toPhoneNumber, message);
    }
}
