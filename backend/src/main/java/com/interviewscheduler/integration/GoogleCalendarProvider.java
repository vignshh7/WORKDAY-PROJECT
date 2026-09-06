package com.interviewscheduler.integration;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.ConferenceData;
import com.google.api.services.calendar.model.ConferenceSolutionKey;
import com.google.api.services.calendar.model.CreateConferenceRequest;
import com.google.api.services.calendar.model.EntryPoint;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.interviewscheduler.common.exception.CalendarIntegrationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Google Calendar provider — active when {@code calendar.provider=google}. Every event is
 * created on the "primary" calendar of whichever single Google account was connected via
 * {@link GoogleOAuthService} (see {@link GoogleOAuthToken} for why this is one org-wide
 * connection, not one per user) — attendees are invited by email address, they don't need
 * their own OAuth connection.
 *
 * <p>{@code sendUpdates("none")} is used on every write: this system already has its own
 * {@code NotificationService} (Phase 22) as the single source of truth for who was told what
 * about an interview, so Google is not asked to also email attendees directly — avoiding
 * duplicate/inconsistent notifications, not an oversight.
 *
 * <p>Error handling contract (inherited from {@link CalendarProvider}):
 * <ul>
 *   <li>401 → the access token was valid by our own recorded expiry but Google rejected it
 *       anyway (e.g. externally revoked and re-granted) — force a refresh and retry exactly
 *       once; if it still fails, throw {@link CalendarIntegrationException}.</li>
 *   <li>404 on cancel → treated as success (event already deleted at Google).</li>
 *   <li>Any other {@link IOException} (timeout, 403, 5xx, etc.) → throws
 *       {@link CalendarIntegrationException} so {@link CalendarSyncService} marks the DB
 *       record FAILED for later retry, without rolling back the booking transaction.</li>
 * </ul>
 *
 * <p><b>Known gap, carried from Phase 19:</b> nothing in the booking/rescheduling orchestration
 * (Phases 12–17) calls {@link #updateEvent} today — a reschedule or interviewer switch always
 * cancels the old calendar event and creates a fresh one via {@link CalendarSyncService},
 * because the new slot can have a different interviewer, different participants, or both, not
 * just a different time on the same attendee list. {@code updateEvent} is implemented here
 * correctly and is ready for a caller, but is currently unreachable via any existing flow —
 * re-plumbing Phases 12–17 to prefer an in-place update is a deliberately separate decision,
 * not made silently as a side effect of this phase.
 */
@Service
@ConditionalOnProperty(name = "calendar.provider", havingValue = "google")
public class GoogleCalendarProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarProvider.class);
    private static final String CALENDAR_ID = "primary";
    private static final String APPLICATION_NAME = "interview-scheduler-backend";

    private final GoogleOAuthTokenService oauthTokenService;

    public GoogleCalendarProvider(GoogleOAuthTokenService oauthTokenService) {
        this.oauthTokenService = oauthTokenService;
    }

    @Override
    public ExternalEventResult createEvent(CalendarEventRequest request) {
        log.debug("[GOOGLE] createEvent roundId={} start={} end={} attendees={}",
                request.roundId(), request.start(), request.end(), request.attendeeEmails());
        Event event = toGoogleEvent(request);
        event.setConferenceData(new ConferenceData().setCreateRequest(
                new CreateConferenceRequest()
                        .setRequestId(UUID.randomUUID().toString())
                        .setConferenceSolutionKey(new ConferenceSolutionKey().setType("hangoutsMeet"))));

        Event created = executeWithRetry(token -> client(token).events().insert(CALENDAR_ID, event)
                .setConferenceDataVersion(1)
                .setSendUpdates("none")
                .execute(), "createEvent");
        return new ExternalEventResult(created.getId(), extractMeetingLink(created));
    }

    @Override
    public ExternalEventResult updateEvent(String externalEventId, CalendarEventRequest request) {
        log.debug("[GOOGLE] updateEvent externalId={} roundId={} start={} end={}",
                externalEventId, request.roundId(), request.start(), request.end());
        Event patch = toGoogleEvent(request);

        Event updated = executeWithRetry(token -> client(token).events().patch(CALENDAR_ID, externalEventId, patch)
                .setConferenceDataVersion(1)
                .setSendUpdates("none")
                .execute(), "updateEvent");
        return new ExternalEventResult(updated.getId(), extractMeetingLink(updated));
    }

    @Override
    public void cancelEvent(String externalEventId) {
        log.debug("[GOOGLE] cancelEvent externalId={}", externalEventId);
        try {
            executeWithRetry(token -> {
                client(token).events().delete(CALENDAR_ID, externalEventId).setSendUpdates("none").execute();
                return null;
            }, "cancelEvent");
        } catch (CalendarIntegrationException e) {
            if (e.getCause() instanceof GoogleJsonResponseException gjre && gjre.getStatusCode() == 404) {
                log.debug("[GOOGLE] cancelEvent externalId={} already gone at Google, treating as success",
                        externalEventId);
                return;
            }
            throw e;
        }
    }

    @Override
    public CalendarEventSnapshot getEvent(String externalEventId) {
        log.debug("[GOOGLE] getEvent externalId={}", externalEventId);
        try {
            Event event = executeWithRetry(token -> client(token).events().get(CALENDAR_ID, externalEventId).execute(),
                    "getEvent");
            List<String> declined = event.getAttendees() == null ? List.of()
                    : event.getAttendees().stream()
                            .filter(a -> "declined".equals(a.getResponseStatus()))
                            .map(EventAttendee::getEmail)
                            .toList();
            return new CalendarEventSnapshot(true, toOffsetDateTime(event.getStart()), toOffsetDateTime(event.getEnd()),
                    declined);
        } catch (CalendarIntegrationException e) {
            if (e.getCause() instanceof GoogleJsonResponseException gjre && gjre.getStatusCode() == 404) {
                return CalendarEventSnapshot.notFound();
            }
            throw e;
        }
    }

    @Override
    public String providerName() {
        return "GOOGLE";
    }

    private OffsetDateTime toOffsetDateTime(EventDateTime eventDateTime) {
        DateTime dateTime = eventDateTime == null ? null : eventDateTime.getDateTime();
        if (dateTime == null) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(dateTime.getValue()), ZoneOffset.UTC);
    }

    private Event toGoogleEvent(CalendarEventRequest request) {
        Event event = new Event()
                .setSummary(request.title())
                .setDescription(request.description())
                .setStart(new EventDateTime()
                        .setDateTime(new DateTime(request.start().toInstant().toEpochMilli()))
                        .setTimeZone(request.timezone()))
                .setEnd(new EventDateTime()
                        .setDateTime(new DateTime(request.end().toInstant().toEpochMilli()))
                        .setTimeZone(request.timezone()));
        if (request.attendeeEmails() != null && !request.attendeeEmails().isEmpty()) {
            event.setAttendees(request.attendeeEmails().stream()
                    .map(email -> new EventAttendee().setEmail(email))
                    .toList());
        }
        return event;
    }

    private String extractMeetingLink(Event event) {
        if (event.getHangoutLink() != null) {
            return event.getHangoutLink();
        }
        if (event.getConferenceData() != null && event.getConferenceData().getEntryPoints() != null) {
            return event.getConferenceData().getEntryPoints().stream()
                    .filter(ep -> "video".equals(ep.getEntryPointType()))
                    .map(EntryPoint::getUri)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private Calendar client(String accessToken) {
        HttpRequestInitializer requestInitializer = request -> {
            request.getHeaders().setAuthorization("Bearer " + accessToken);
            request.setConnectTimeout(10_000);
            request.setReadTimeout(30_000);
        };
        return new Calendar.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private interface GoogleApiCall<T> {
        T call(String accessToken) throws IOException;
    }

    /** Runs a Google API call; on a 401 refreshes the token once and retries exactly once. */
    private <T> T executeWithRetry(GoogleApiCall<T> call, String operationName) {
        String accessToken = oauthTokenService.getValidAccessToken();
        try {
            return call.call(accessToken);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 401) {
                log.warn("[GOOGLE] {} got 401 despite a fresh token — forcing refresh and retrying once",
                        operationName);
                String refreshedToken = oauthTokenService.forceRefreshAccessToken();
                try {
                    return call.call(refreshedToken);
                } catch (IOException retryEx) {
                    throw new CalendarIntegrationException(
                            "Google Calendar " + operationName + " failed after token refresh: "
                            + retryEx.getMessage(), retryEx);
                }
            }
            throw new CalendarIntegrationException(
                    "Google Calendar " + operationName + " failed (HTTP " + e.getStatusCode() + "): "
                    + e.getMessage(), e);
        } catch (IOException e) {
            throw new CalendarIntegrationException(
                    "Google Calendar " + operationName + " failed: " + e.getMessage(), e);
        }
    }
}
