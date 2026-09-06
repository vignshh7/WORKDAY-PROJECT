package com.interviewscheduler.availability;

import com.interviewscheduler.common.exception.ConflictException;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every user manages only their own availability (POST always creates for the caller; PUT/DELETE
 * require ownership) — the spec calls this out explicitly for candidates and interviewers, and
 * nothing suggests staff should be able to edit someone else's personal calendar on their
 * behalf. Staff (RECRUITER/ADMIN) can still read anyone's, since the scheduling engine (and a
 * recruiter arranging an interview) needs to see it.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private final AvailabilityRepository availabilityRepository;
    private final UserRepository userRepository;
    private final WorkingHoursService workingHoursService;
    private final TimezoneService timezoneService;

    @Transactional
    public AvailabilityResponse create(AvailabilityRequest request) {
        UserPrincipal caller = SecurityUtils.currentUser();
        User user = userRepository.getReferenceById(caller.getId());

        validatePolicy(request);
        checkOverlap(caller.getId(), request, null);

        Availability availability = new Availability();
        availability.setUser(user);
        applyRequest(availability, request);

        return AvailabilityResponse.from(availabilityRepository.saveAndFlush(availability));
    }

    @Transactional(readOnly = true)
    public List<AvailabilityResponse> findByUser(UUID userId) {
        assertReadAccess(userId);
        return availabilityRepository.findByUserId(userId).stream().map(AvailabilityResponse::from).toList();
    }

    @Transactional
    public AvailabilityResponse update(UUID id, AvailabilityRequest request) {
        Availability availability = getOwned(id);

        validatePolicy(request);
        checkOverlap(availability.getUser().getId(), request, id);

        applyRequest(availability, request);
        return AvailabilityResponse.from(availabilityRepository.saveAndFlush(availability));
    }

    @Transactional
    public void delete(UUID id) {
        availabilityRepository.delete(getOwned(id));
    }

    private void applyRequest(Availability availability, AvailabilityRequest request) {
        availability.setDate(request.date());
        availability.setStartTime(request.startTime());
        availability.setEndTime(request.endTime());
        availability.setStatus(request.status());
        availability.setTimezone(request.timezone());
    }

    /** Working hours and the weekend policy only constrain a claim of being AVAILABLE. */
    private void validatePolicy(AvailabilityRequest request) {
        if (request.status() != AvailabilityStatus.AVAILABLE) {
            return;
        }
        if (!workingHoursService.isWithinWorkingHours(request.startTime(), request.endTime())) {
            throw new ConflictException("Availability window is outside the configured working hours");
        }
        if (workingHoursService.isWeekend(request.date()) && !workingHoursService.weekendsAllowed()) {
            throw new ConflictException("Weekend availability is not allowed by the current scheduling configuration");
        }
    }

    /**
     * Compares UTC-normalized instant ranges (not raw date/time fields) so overlap is detected
     * correctly even when two entries for the same user use different timezones. The +-1 day
     * window comfortably covers every real-world UTC offset (-12 to +14).
     */
    private void checkOverlap(UUID userId, AvailabilityRequest request, UUID excludeId) {
        TimezoneService.TimeRange newRange = timezoneService.toUtcRange(
                request.date(), request.startTime(), request.endTime(), request.timezone());

        List<Availability> nearby = availabilityRepository.findByUserIdAndDateBetween(
                userId, request.date().minusDays(1), request.date().plusDays(1));

        for (Availability existing : nearby) {
            if (existing.getId().equals(excludeId)) {
                continue;
            }
            TimezoneService.TimeRange existingRange = timezoneService.toUtcRange(
                    existing.getDate(), existing.getStartTime(), existing.getEndTime(), existing.getTimezone());
            if (newRange.start().isBefore(existingRange.end()) && existingRange.start().isBefore(newRange.end())) {
                throw new ConflictException("Availability window overlaps an existing entry for this user");
            }
        }
    }

    private Availability getOwned(UUID id) {
        Availability availability = availabilityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No availability entry with id: " + id));
        UserPrincipal caller = SecurityUtils.currentUser();
        if (!availability.getUser().getId().equals(caller.getId())) {
            throw new ForbiddenException("Cannot modify another user's availability");
        }
        return availability;
    }

    private void assertReadAccess(UUID userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("No user with id: " + userId);
        }
        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isStaff = caller.getRole() == Role.RECRUITER || caller.getRole() == Role.ADMIN;
        boolean isSelf = caller.getId().equals(userId);
        if (!isStaff && !isSelf) {
            throw new ForbiddenException("Cannot view another user's availability");
        }
    }
}
