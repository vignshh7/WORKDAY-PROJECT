package com.interviewscheduler.availability;

import com.interviewscheduler.candidate.Candidate;
import com.interviewscheduler.candidate.CandidateRepository;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Role-scoped availability lookup keyed by candidate profile id rather than user id — the seam
 * Phase 11's slot finder ("get candidate availability") calls without needing to know the
 * candidate-to-user id mapping itself.
 */
@Service
@RequiredArgsConstructor
public class CandidateAvailabilityService {

    private final CandidateRepository candidateRepository;
    private final AvailabilityRepository availabilityRepository;

    @Transactional(readOnly = true)
    public List<AvailabilityResponse> getAvailability(UUID candidateId, LocalDate from, LocalDate to) {
        Candidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("No candidate with id: " + candidateId));
        return availabilityRepository.findByUserIdAndDateBetween(candidate.getUser().getId(), from, to).stream()
                .map(AvailabilityResponse::from)
                .toList();
    }
}
