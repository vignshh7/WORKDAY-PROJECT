package com.interviewscheduler.integration;

import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.interviewscheduler.common.exception.CalendarIntegrationException;
import com.interviewscheduler.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists and refreshes the single org-wide Google Calendar OAuth connection (see
 * {@link GoogleOAuthToken}). {@link GoogleCalendarProvider} calls {@link #getValidAccessToken()}
 * before every API call rather than trusting a cached token's expiry blindly - refresh happens
 * here, transparently, so the provider layer never has to think about token lifecycle.
 */
@Service
@RequiredArgsConstructor
public class GoogleOAuthTokenService {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthTokenService.class);

    /** Refresh this many seconds before actual expiry, to avoid a request racing the deadline. */
    private static final int EXPIRY_SAFETY_MARGIN_SECONDS = 60;

    @Value("${GOOGLE_CLIENT_ID:}")
    private String clientId;

    @Value("${GOOGLE_CLIENT_SECRET:}")
    private String clientSecret;

    private final GoogleOAuthTokenRepository tokenRepository;
    private final UserRepository userRepository;

    /**
     * Returns a currently-valid access token, refreshing the stored one first if it is expired
     * or about to expire. Throws {@link CalendarIntegrationException} if Google Calendar has
     * never been connected, or if the refresh call itself fails (e.g. the refresh token was
     * revoked - the connection then needs to be re-authorized via {@link GoogleOAuthService}).
     *
     * <p><b>{@code REQUIRES_NEW}, not the default {@code REQUIRED}:</b> {@link GoogleCalendarProvider}
     * calls this from inside whatever larger transaction is booking/rescheduling/cancelling an
     * interview (see {@link CalendarSyncService}'s "runs within the caller's existing
     * {@code @Transactional} unit" contract), and that caller is relying on being able to
     * catch-and-swallow a thrown {@link CalendarIntegrationException} without the booking
     * itself being rolled back. With the default {@code REQUIRED} propagation this method would
     * join that same physical transaction, and Spring's transactional advice marks the whole
     * thing rollback-only the instant an unchecked exception crosses *this* method's boundary —
     * regardless of whether something further up the stack later catches it — which surfaces to
     * the caller as {@code UnexpectedRollbackException} on commit, silently discarding an
     * otherwise-successful booking. {@code REQUIRES_NEW} keeps that blast radius contained to
     * this method's own transaction, exactly like every other {@code CalendarProvider} failure
     * mode already does. (Caught live in Phase 20 verification, not a hypothetical.)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String getValidAccessToken() {
        GoogleOAuthToken token = tokenRepository.findTopByOrderByUpdatedAtDesc()
                .orElseThrow(() -> new CalendarIntegrationException(
                        "Google Calendar is not connected. An admin must authorize it first "
                        + "via GET /api/integrations/google-calendar/authorize."));

        if (token.getExpiresAt().isBefore(OffsetDateTime.now().plusSeconds(EXPIRY_SAFETY_MARGIN_SECONDS))) {
            refresh(token);
        }
        return token.getAccessToken();
    }

    /**
     * Unconditionally refreshes the access token, regardless of its recorded expiry. Used by
     * {@link GoogleCalendarProvider} as the one-retry-after-401 step in its documented error
     * contract, for the case where Google invalidated the token before our recorded expiry
     * (e.g. the connected account's access was revoked and later re-granted). Same
     * {@code REQUIRES_NEW} reasoning as {@link #getValidAccessToken()}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String forceRefreshAccessToken() {
        GoogleOAuthToken token = tokenRepository.findTopByOrderByUpdatedAtDesc()
                .orElseThrow(() -> new CalendarIntegrationException(
                        "Google Calendar is not connected. An admin must authorize it first "
                        + "via GET /api/integrations/google-calendar/authorize."));
        refresh(token);
        return token.getAccessToken();
    }

    private void refresh(GoogleOAuthToken token) {
        try {
            GoogleTokenResponse response = new GoogleRefreshTokenRequest(
                    new NetHttpTransport(), GsonFactory.getDefaultInstance(),
                    token.getRefreshToken(), clientId, clientSecret)
                    .execute();
            token.setAccessToken(response.getAccessToken());
            token.setExpiresAt(OffsetDateTime.now().plusSeconds(response.getExpiresInSeconds()));
            if (response.getRefreshToken() != null) {
                token.setRefreshToken(response.getRefreshToken());
            }
            tokenRepository.save(token);
            log.debug("Refreshed Google Calendar OAuth access token, new expiry={}", token.getExpiresAt());
        } catch (IOException e) {
            throw new CalendarIntegrationException(
                    "Failed to refresh Google Calendar access token - the connection may need "
                    + "to be re-authorized: " + e.getMessage(), e);
        }
    }

    /** Upserts the single stored connection row - see {@link GoogleOAuthToken} javadoc. */
    @Transactional
    public GoogleOAuthToken saveTokens(GoogleTokenResponse tokenResponse, UUID connectedByUserId) {
        Optional<GoogleOAuthToken> existing = tokenRepository.findTopByOrderByUpdatedAtDesc();
        if (tokenResponse.getRefreshToken() == null && existing.isEmpty()) {
            throw new CalendarIntegrationException(
                    "Google did not return a refresh token and no prior connection exists. "
                    + "Revoke the app's access at https://myaccount.google.com/permissions "
                    + "and re-authorize so Google issues a fresh refresh token.");
        }

        GoogleOAuthToken token = existing.orElseGet(GoogleOAuthToken::new);
        token.setAccessToken(tokenResponse.getAccessToken());
        if (tokenResponse.getRefreshToken() != null) {
            token.setRefreshToken(tokenResponse.getRefreshToken());
        }
        token.setExpiresAt(OffsetDateTime.now().plusSeconds(tokenResponse.getExpiresInSeconds()));
        token.setScope(tokenResponse.getScope());
        token.setConnectedByUser(userRepository.getReferenceById(connectedByUserId));
        return tokenRepository.save(token);
    }

    @Transactional(readOnly = true)
    public Optional<GoogleOAuthToken> currentConnection() {
        return tokenRepository.findTopByOrderByUpdatedAtDesc();
    }
}
