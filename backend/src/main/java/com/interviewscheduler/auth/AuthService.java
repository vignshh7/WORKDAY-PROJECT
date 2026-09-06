package com.interviewscheduler.auth;

import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.exception.DuplicateResourceException;
import com.interviewscheduler.common.exception.UnauthorizedException;
import com.interviewscheduler.security.JwtService;
import com.interviewscheduler.security.UserPrincipal;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserRepository;
import com.interviewscheduler.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AuditService auditService;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email already registered: " + request.email());
        }

        User user = new User();
        user.setName(request.name());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        if (request.timezone() != null && !request.timezone().isBlank()) {
            user.setTimezone(request.timezone());
        }

        // saveAndFlush (not save): @CreationTimestamp/@UpdateTimestamp populate the entity
        // at flush time, and the response is built immediately after, inside the same
        // transaction — without a flush here, createdAt/updatedAt would still be null.
        User saved = userRepository.saveAndFlush(user);

        // Self-registration: there is no authenticated caller yet, so the actor is the
        // account that was just created, not SecurityUtils.currentUser().
        auditService.log(saved.getId(), ActorType.USER, AuditAction.USER_CREATED,
                "USER", saved.getId(), Map.of("role", saved.getRole().name()));

        return UserResponse.from(saved);
    }

    public LoginResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email(), request.password()));
        } catch (AuthenticationException e) {
            // Covers bad password, unknown email, and disabled/locked (INACTIVE/SUSPENDED)
            // accounts uniformly — never reveal which one it was.
            throw new UnauthorizedException("Invalid email or password");
        }

        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        String token = jwtService.generateToken(principal.getId(), principal.getUsername(), principal.getRole());
        return LoginResponse.bearer(token, principal.getId(), principal.getUsername(), principal.getRole());
    }
}
