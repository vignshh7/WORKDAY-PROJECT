package com.interviewscheduler.integration;

import com.interviewscheduler.common.exception.CalendarIntegrationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Thin wrapper around {@link CalendarProvider#getBusyIntervals} that makes the "purely additive,
 * never a hard blocker" contract explicit at the one place both {@code SlotFinderService} and
 * {@code InterviewBookingService} call into: a user who hasn't connected Google Calendar, or a
 * provider failure, always reads as "no extra busy data" rather than failing the caller — this
 * system's own {@code Availability} records remain the source of truth (see the master spec:
 * "AI must never invent availability"; the same principle applies to an external calendar this
 * system doesn't control the correctness of).
 */
@Service
@RequiredArgsConstructor
public class CalendarBusyTimeService {

    private static final Logger log = LoggerFactory.getLogger(CalendarBusyTimeService.class);

    private final CalendarProvider calendarProvider;

    public List<BusyInterval> busyIntervals(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        try {
            return calendarProvider.getBusyIntervals(userId, from, to);
        } catch (CalendarIntegrationException e) {
            log.debug("No Google Calendar busy data for userId={} (not connected or lookup failed): {}",
                    userId, e.getMessage());
            return List.of();
        }
    }
}
