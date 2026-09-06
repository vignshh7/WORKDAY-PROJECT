package com.interviewscheduler.integration;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {

    List<CalendarEvent> findByInterviewRoundId(UUID interviewRoundId);

    List<CalendarEvent> findByStatus(CalendarEventStatus status);
}
