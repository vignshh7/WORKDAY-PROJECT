package com.interviewscheduler.integration;

import com.interviewscheduler.common.exception.CalendarIntegrationException;
import com.interviewscheduler.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

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
 *
 * <p><b>Frontend integration:</b> the intended flow is: frontend calls {@code GET /authorize}
 * (with the user's JWT, like any other API call) and navigates the browser to the returned URL;
 * Google then redirects the browser straight to this backend's {@code /callback} (it has no way
 * to reach the frontend directly, or to carry the JWT). If {@code APP_BASE_URL} is configured,
 * {@code /callback} finishes by redirecting the browser on to
 * {@code {APP_BASE_URL}/settings/integrations?google=connected} (or {@code ...&google=error&message=...}
 * on failure) so the user lands back in the app's own UI instead of on a bare backend page -
 * the frontend should render its "Connect Google Calendar" settings page at that path and read
 * the {@code google}/{@code message} query params to show a result. Without {@code APP_BASE_URL}
 * set (e.g. no frontend yet), {@code /callback} falls back to a plain HTML success page, or lets
 * a failure surface as the usual JSON error - both fine for manual/API-only testing.
 */
@RestController
@RequestMapping("/api/integrations/google-calendar")
@RequiredArgsConstructor
public class GoogleCalendarOAuthController {

    private final GoogleOAuthService googleOAuthService;
    private final GoogleOAuthTokenService googleOAuthTokenService;

    @Value("${calendar.provider:noop}")
    private String activeProvider;

    @Value("${app.base-url:}")
    private String appBaseUrl;

    @GetMapping("/authorize")
    public GoogleAuthorizationUrlResponse authorize() {
        String url = googleOAuthService.buildAuthorizationUrl(SecurityUtils.currentUser().getId());
        return new GoogleAuthorizationUrlResponse(url);
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam String code, @RequestParam String state) {
        try {
            googleOAuthService.handleCallback(code, state);
        } catch (CalendarIntegrationException e) {
            if (!appBaseUrl.isBlank()) {
                return redirectTo(UriComponentsBuilder.fromUriString(appBaseUrl + "/settings/integrations")
                        .queryParam("google", "error")
                        .queryParam("message", e.getMessage())
                        .build().toUriString());
            }
            throw e;
        }

        if (!appBaseUrl.isBlank()) {
            return redirectTo(UriComponentsBuilder.fromUriString(appBaseUrl + "/settings/integrations")
                    .queryParam("google", "connected")
                    .build().toUriString());
        }
        String html = "<html><body><h3>Google Calendar connected.</h3>"
                + "<p>You can close this window.</p></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    private ResponseEntity<String> redirectTo(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location).build();
    }

    @GetMapping("/status")
    @Transactional(readOnly = true)
    public GoogleCalendarStatusResponse status() {
        return googleOAuthTokenService.currentConnection(SecurityUtils.currentUser().getId())
                .map(token -> GoogleCalendarStatusResponse.connected(activeProvider, token))
                .orElseGet(() -> GoogleCalendarStatusResponse.notConnected(activeProvider));
    }
}
