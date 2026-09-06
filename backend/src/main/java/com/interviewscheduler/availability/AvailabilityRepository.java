package com.interviewscheduler.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AvailabilityRepository extends JpaRepository<Availability, UUID> {

    List<Availability> findByUserId(UUID userId);

    List<Availability> findByUserIdAndDateBetween(UUID userId, LocalDate from, LocalDate to);

    List<Availability> findByUserIdAndDate(UUID userId, LocalDate date);
}
