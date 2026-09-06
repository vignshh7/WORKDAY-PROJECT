package com.interviewscheduler.integration;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.interviewscheduler.audit.ActorType;
import com.interviewscheduler.audit.AuditAction;
import com.interviewscheduler.audit.AuditService;
import com.interviewscheduler.common.exception.CalendarIntegrationException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the Google OAuth2 authorization-code flow that connects one Google account's calendar
 * to this backend (see {@link GoogleOAuthToken} for why it's one org-wide connection, not one
 * per user). {@link GoogleOAuthTokenService} owns what happens to the tokens once obtained;
 * this class only owns the authorize/callback handshake.
 */
@Service
@RequiredArgsConstructor
public class GoogleOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthService.class);
    private static final String SCOPE = "https://www.googleapis.com/auth/calendar.events";
    private static final long STATE_TTL_MINUTES = 10;

    @Value("${GOOGLE_CLIENT_ID:}")
    private String clientId;

    @Value("${GOOGLE_CLIENT_SECRET:}")
    private String clientSecret;

    @Value("${GOOGLE_REDIRECT_URI:}")
    private String redirectUri;

    private final GoogleOAuthTokenService tokenService;
    private final AuditService auditService;

    /** state -> who initiated + when it expires. CSRF protection for the callback redirect. */
    private final Map<String, PendingAuthorization> pendingByState = new ConcurrentHashMap<>();

    private record PendingAuthorization(UUID initiatedByUserId, Instant expiresAt) {
    }

    public String buildAuthorizationUrl(UUID initiatingUserId) {
        requireConfigured();
        String state = UUID.randomUUID().toString();
        pendingByState.put(state, new PendingAuthorization(initiatingUserId, Instant.now().plus(STATE_TTL_MINUTES, ChronoUnit.MINUTES)));

        GoogleAuthorizationCodeFlow flow = buildFlow();
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(state)
                .set("access_type", "offline")
                .set("prompt", "consent") // force a refresh_token even on re-authorization
                .build();
    }

    /** Exchanges the authorization code for tokens and persists the connection. */
    public void handleCallback(String code, String state) {
        requireConfigured();
        PendingAuthorization pending = pendingByState.remove(state);
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new CalendarIntegrationException("Invalid or expired OAuth state - restart the authorization flow.");
        }

        try {
            GoogleAuthorizationCodeFlow flow = buildFlow();
            GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
                    .setRedirectUri(redirectUri)
                    .execute();

            tokenService.saveTokens(tokenResponse, pending.initiatedByUserId());

            auditService.log(pending.initiatedByUserId(), ActorType.USER, AuditAction.GOOGLE_CALENDAR_CONNECTED,
                    "GOOGLE_OAUTH_TOKEN", null, Map.of("scope", tokenResponse.getScope()));
            log.info("Google Calendar connected by userId={}", pending.initiatedByUserId());
        } catch (IOException | CalendarIntegrationException e) {
            auditService.log(pending.initiatedByUserId(), ActorType.USER, AuditAction.GOOGLE_CALENDAR_CONNECTION_FAILED,
                    "GOOGLE_OAUTH_TOKEN", null, Map.of("error", e.getMessage()));
            throw new CalendarIntegrationException("Failed to connect Google Calendar: " + e.getMessage(), e);
        }
    }

    private GoogleAuthorizationCodeFlow buildFlow() {
        try {
            return new GoogleAuthorizationCodeFlow.Builder(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                    clientId, clientSecret, List.of(SCOPE))
                    .setAccessType("offline")
                    .build();
        } catch (Exception e) {
            throw new CalendarIntegrationException("Failed to initialize Google OAuth flow: " + e.getMessage(), e);
        }
    }

    private void requireConfigured() {
        if (clientId.isBlank() || clientSecret.isBlank() || redirectUri.isBlank()) {
            throw new CalendarIntegrationException(
                    "Google OAuth is not configured - set GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET "
                    + "and GOOGLE_REDIRECT_URI.");
        }
    }
}
