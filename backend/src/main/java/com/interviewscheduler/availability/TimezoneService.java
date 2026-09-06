package com.interviewscheduler.availability;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Converts wall-clock availability windows to absolute instants so windows recorded in
 * different timezones (or on either side of a DST transition) can be compared correctly.
 * {@link ZonedDateTime} already resolves the right UTC offset for the given date/zone, so
 * DST-awareness falls out of using it rather than a fixed offset.
 */
@Service
public class TimezoneService {

    public TimeRange toUtcRange(LocalDate date, LocalTime start, LocalTime end, String zoneId) {
        ZoneId zone = ZoneId.of(zoneId);
        return new TimeRange(
                ZonedDateTime.of(date, start, zone).toInstant(),
                ZonedDateTime.of(date, end, zone).toInstant());
    }

    public record TimeRange(Instant start, Instant end) {
    }
}
