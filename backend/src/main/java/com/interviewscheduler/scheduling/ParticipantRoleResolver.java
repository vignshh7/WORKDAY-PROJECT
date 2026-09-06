package com.interviewscheduler.scheduling;

import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.interview.ParticipantRole;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Shared by {@link SlotFinderService} and {@link InterviewBookingService} to classify a
 * "required participant" user id for conflict checking. No {@code User.Role} maps to
 * {@link ParticipantRole#HIRING_MANAGER} (the schema has no such account role) - a required
 * participant is classified as {@code RECRUITER} unless their account role is literally
 * {@code CANDIDATE} or {@code INTERVIEWER}.
 */
@Component
@RequiredArgsConstructor
public class ParticipantRoleResolver {

    private final UserRepository userRepository;

    public CheckConflictsRequest.RequiredParticipant resolve(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + userId));
        ParticipantRole role = switch (user.getRole()) {
            case CANDIDATE -> ParticipantRole.CANDIDATE;
            case INTERVIEWER -> ParticipantRole.INTERVIEWER;
            case RECRUITER, ADMIN -> ParticipantRole.RECRUITER;
        };
        return new CheckConflictsRequest.RequiredParticipant(userId, role);
    }
}
