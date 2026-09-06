package com.interviewscheduler.job;

import java.time.OffsetDateTime;
import java.util.UUID;

public record JobResponse(
        UUID id,
        String title,
        String description,
        String department,
        String domain,
        JobStatus status,
        UUID createdByUserId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getTitle(),
                job.getDescription(),
                job.getDepartment(),
                job.getDomain(),
                job.getStatus(),
                job.getCreatedBy() == null ? null : job.getCreatedBy().getId(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
