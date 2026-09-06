package com.interviewscheduler.security;

import com.interviewscheduler.user.Role;
import com.interviewscheduler.user.User;
import com.interviewscheduler.user.UserStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public class UserPrincipal implements UserDetails {

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    private UserPrincipal(UUID id, String email, String passwordHash, Role role, boolean enabled) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    /** Built from a DB lookup (login flow) — carries the real password hash and status. */
    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(
                user.getId(), user.getEmail(), user.getPasswordHash(),
                user.getRole(), user.getStatus() == UserStatus.ACTIVE);
    }

    /**
     * Built from an already-validated JWT (per-request filter path) — no DB hit, no
     * password hash. Trusted because JwtService already verified the signature.
     */
    public static UserPrincipal fromClaims(UUID id, String email, Role role) {
        return new UserPrincipal(id, email, null, role, true);
    }

    public UUID getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
