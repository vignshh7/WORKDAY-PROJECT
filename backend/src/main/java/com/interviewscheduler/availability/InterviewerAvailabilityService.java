package com.interviewscheduler.availability;

import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interviewer.InterviewerProfile;
import com.interviewscheduler.interviewer.InterviewerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Role-scoped availability lookup keyed by interviewer profile id rather than user id — the
 * seam Phase 11's slot finder ("get interviewer availability") calls without needing to know
 * the interviewer-to-user id mapping itself.
 */
@Service
@RequiredArgsConstructor
public class InterviewerAvailabilityService {

    private final InterviewerProfileRepository interviewerProfileRepository;
    private final AvailabilityRepository availabilityRepository;

    @Transactional(readOnly = true)
    public List<AvailabilityResponse> getAvailability(UUID interviewerId, LocalDate from, LocalDate to) {
        InterviewerProfile interviewer = interviewerProfileRepository.findById(interviewerId)
                .orElseThrow(() -> new ResourceNotFoundException("No interviewer with id: " + interviewerId));
        return availabilityRepository.findByUserIdAndDateBetween(interviewer.getUser().getId(), from, to).stream()
                .map(AvailabilityResponse::from)
                .toList();
    }
}
