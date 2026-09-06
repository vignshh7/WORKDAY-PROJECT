package com.interviewscheduler.security;

import com.interviewscheduler.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Object-level authorization (e.g. "a candidate may only read their own profile") is not a
 * @PreAuthorize role check — it needs the resource's owner id compared against the caller.
 * Services from Phase 6 onward call currentUser() and compare ids/roles themselves.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UserPrincipal currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new UnauthorizedException("No authenticated user in context");
        }
        return principal;
    }
}
