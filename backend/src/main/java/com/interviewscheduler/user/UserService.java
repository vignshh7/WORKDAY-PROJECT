package com.interviewscheduler.user;

import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.exception.ForbiddenException;
import com.interviewscheduler.common.exception.ResourceNotFoundException;
import com.interviewscheduler.security.SecurityUtils;
import com.interviewscheduler.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<UserResponse> findAll() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse findById(UUID id) {
        return UserResponse.from(getOwnedOrAdmin(id));
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        User user = getOwnedOrAdmin(id);
        user.setName(request.name());
        user.setTimezone(request.timezone());
        return UserResponse.from(userRepository.saveAndFlush(user));
    }

    @Transactional
    public UserResponse updateStatus(UUID id, UpdateUserStatusRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + id));
        UserStatus previous = user.getStatus();
        user.setStatus(request.status());
        User saved = userRepository.saveAndFlush(user);

        auditService.logForCurrentUser(AuditAction.USER_STATUS_CHANGED, "USER", saved.getId(),
                Map.of("from", previous.name(), "to", saved.getStatus().name()));

        return UserResponse.from(saved);
    }

    /** ADMIN can act on any user; anyone else only on their own record. */
    private User getOwnedOrAdmin(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id: " + id));

        UserPrincipal caller = SecurityUtils.currentUser();
        boolean isSelf = caller.getId().equals(id);
        boolean isAdmin = caller.getRole() == Role.ADMIN;
        if (!isSelf && !isAdmin) {
            throw new ForbiddenException("Cannot access another user's record");
        }
        return user;
    }
}
