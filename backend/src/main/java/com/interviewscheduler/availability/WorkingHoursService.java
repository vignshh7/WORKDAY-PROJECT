package com.interviewscheduler.availability;

import com.interviewscheduler.admin.SchedulingConfig;
import com.interviewscheduler.admin.SchedulingConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/** Reads the single {@code scheduling_config} row (seeded by V17) that governs the whole engine. */
@Service
@RequiredArgsConstructor
public class WorkingHoursService {

    private final SchedulingConfigRepository schedulingConfigRepository;

    @Transactional(readOnly = true)
    public SchedulingConfig currentConfig() {
        return schedulingConfigRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No scheduling_config row found; the V17 migration should have seeded one"));
    }

    public boolean isWithinWorkingHours(LocalTime start, LocalTime end) {
        SchedulingConfig config = currentConfig();
        return !start.isBefore(config.getWorkingStart()) && !end.isAfter(config.getWorkingEnd());
    }

    public boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    public boolean weekendsAllowed() {
        return currentConfig().isAllowWeekends();
    }
}
