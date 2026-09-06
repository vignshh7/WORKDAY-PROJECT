package com.interviewscheduler.integration;

import java.time.OffsetDateTime;

public record GoogleCalendarStatusResponse(
        String activeProvider,
        boolean connected,
        String connectedByEmail,
        OffsetDateTime expiresAt
) {
    public static GoogleCalendarStatusResponse notConnected(String activeProvider) {
        return new GoogleCalendarStatusResponse(activeProvider, false, null, null);
    }

    public static GoogleCalendarStatusResponse connected(String activeProvider, GoogleOAuthToken token) {
        return new GoogleCalendarStatusResponse(
                activeProvider, true, token.getConnectedByUser().getEmail(), token.getExpiresAt());
    }
}
