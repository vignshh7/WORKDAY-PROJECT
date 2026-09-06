package com.interviewscheduler.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SchedulingConfigRepository extends JpaRepository<SchedulingConfig, UUID> {
}
