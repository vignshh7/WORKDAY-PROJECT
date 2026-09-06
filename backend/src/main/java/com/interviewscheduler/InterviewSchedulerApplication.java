package com.interviewscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling backs Phase 23's reminder scan and notification-retry jobs (both @Scheduled).
@SpringBootApplication
@EnableScheduling
public class InterviewSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewSchedulerApplication.class, args);
    }
}
