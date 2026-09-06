package com.interviewscheduler.integration;

import com.interviewscheduler.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 20: connects this backend to one Google account's calendar. {@code /authorize} and
 * {@code /callback} implement the OAuth2 authorization-code handshake; {@code /status} lets
 * RECRUITER/ADMIN confirm the connection without needing DB access.
 *
 * <p>{@code /callback} is deliberately public (see {@code SecurityConfig}) — Google redirects
 * the admin's browser to it directly after consent, with no way to attach our JWT. CSRF is
 * instead prevented by the one-time {@code state} value {@link GoogleOAuthService} issues and
 * validates.
 */
@RestController
@RequestMapping("/api/integrations/google-calendar")
@RequiredArgsConstructor
public class GoogleCalendarOAuthController {

    private final GoogleOAuthService googleOAuthService;
    private final GoogleOAuthTokenService googleOAuthTokenService;

    @Value("${calendar.provider:noop}")
    private String activeProvider;

    @PreAuthorize("hasAnyRole('ADMIN')")
    @GetMapping("/authorize")
    public GoogleAuthorizationUrlResponse authorize() {
        String url = googleOAuthService.buildAuthorizationUrl(SecurityUtils.currentUser().getId());
        return new GoogleAuthorizationUrlResponse(url);
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state) {
        googleOAuthService.handleCallback(code, state);
        String html = "<html><body><h3>Google Calendar connected.</h3>"
                + "<p>You can close this window.</p></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    @PreAuthorize("hasAnyRole('RECRUITER', 'ADMIN')")
    @GetMapping("/status")
    @Transactional(readOnly = true)
    public GoogleCalendarStatusResponse status() {
        return googleOAuthTokenService.currentConnection()
                .map(token -> GoogleCalendarStatusResponse.connected(activeProvider, token))
                .orElseGet(() -> GoogleCalendarStatusResponse.notConnected(activeProvider));
    }
}
