package com.interviewscheduler.integration;

import java.time.OffsetDateTime;

/**
 * One busy period read back from a user's own Google Calendar via
 * {@link CalendarProvider#getBusyIntervals}. Used to overlay a user's real calendar commitments
 * on top of this system's own {@code Availability} records during slot finding - see
 * {@code CalendarBusyTimeService}.
 */
public record BusyInterval(OffsetDateTime start, OffsetDateTime end) {
}
