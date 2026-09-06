package com.interviewscheduler.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "scheduling_config")
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(of = "id")
public class SchedulingConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "working_start", nullable = false)
    private LocalTime workingStart = LocalTime.of(9, 0);

    @Column(name = "working_end", nullable = false)
    private LocalTime workingEnd = LocalTime.of(18, 0);

    @Column(name = "default_buffer_minutes", nullable = false)
    private Integer defaultBufferMinutes = 15;

    @Column(name = "minimum_booking_notice_minutes", nullable = false)
    private Integer minimumBookingNoticeMinutes = 60;

    @Column(name = "maximum_scheduling_days", nullable = false)
    private Integer maximumSchedulingDays = 30;

    @Column(name = "allow_weekends", nullable = false)
    private boolean allowWeekends = false;

    @Column(name = "maximum_reschedules", nullable = false)
    private Integer maximumReschedules = 3;

    @Enumerated(EnumType.STRING)
    @Column(name = "interviewer_replacement_policy", nullable = false, length = 30)
    private ReplacementPolicy interviewerReplacementPolicy = ReplacementPolicy.ASK_BEFORE_REPLACEMENT;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
