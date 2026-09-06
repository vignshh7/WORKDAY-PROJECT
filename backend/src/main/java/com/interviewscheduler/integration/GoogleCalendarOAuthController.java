package com.interviewscheduler.integration;

import com.interviewscheduler.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connects a user's own Google Calendar to this backend - self-service, for any authenticated
 * user (candidate, interviewer, recruiter, or admin): each user has a "Connect Google Calendar"
 * option and only ever manages their own connection. {@code /authorize} and {@code /callback}
 * implement the OAuth2 authorization-code handshake; {@code /status} reports the calling user's
 * own connection.
 *
 * <p>{@code /callback} is deliberately public (see {@code SecurityConfig}) — Google redirects
 * the user's browser to it directly after consent, with no way to attach our JWT. CSRF is
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

    @GetMapping("/status")
    @Transactional(readOnly = true)
    public GoogleCalendarStatusResponse status() {
        return googleOAuthTokenService.currentConnection(SecurityUtils.currentUser().getId())
                .map(token -> GoogleCalendarStatusResponse.connected(activeProvider, token))
                .orElseGet(() -> GoogleCalendarStatusResponse.notConnected(activeProvider));
    }
}
