package com.interviewscheduler.integration;

import java.time.OffsetDateTime;

/** Reports the calling user's own Google Calendar connection - see {@link GoogleOAuthToken}. */
public record GoogleCalendarStatusResponse(
        String activeProvider,
        boolean connected,
        String connectedEmail,
        OffsetDateTime expiresAt
) {
    public static GoogleCalendarStatusResponse notConnected(String activeProvider) {
        return new GoogleCalendarStatusResponse(activeProvider, false, null, null);
    }

    public static GoogleCalendarStatusResponse connected(String activeProvider, GoogleOAuthToken token) {
        return new GoogleCalendarStatusResponse(
                activeProvider, true, token.getUser().getEmail(), token.getExpiresAt());
    }
}
