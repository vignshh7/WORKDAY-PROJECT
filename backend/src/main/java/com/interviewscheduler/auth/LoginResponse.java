package com.interviewscheduler.auth;

import com.interviewscheduler.user.Role;

import java.util.UUID;

public record LoginResponse(
        String token,
        String tokenType,
        UUID userId,
        String email,
        Role role
) {
    public static LoginResponse bearer(String token, UUID userId, String email, Role role) {
        return new LoginResponse(token, "Bearer", userId, email, role);
    }
}
