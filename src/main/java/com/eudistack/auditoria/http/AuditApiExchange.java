package com.eudistack.auditoria.http;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.EventResult;
import com.eudistack.auditoria.domain.EventStatus;
import com.eudistack.auditoria.domain.OutboxLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What both clients share: the request body, the reading of the response and the JDK HTTP client (no redirects,
 * {@link AuditApiTimeouts#CONNECT}). Never logs nor returns the payload.
 */
final class AuditApiExchange {

    static final String TRACEPARENT = "traceparent";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CAUSE_LENGTH = 200;

    private AuditApiExchange() {
    }

    static HttpClient jdkClient() {
        return HttpClient.newBuilder()
                .connectTimeout(AuditApiTimeouts.CONNECT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** {@code {"events":[...]}} with each stored payload as it is: no re-serialization. */
    static String body(List<ClaimedEvent> events) {
        if (events == null || events.isEmpty() || events.size() > OutboxLimits.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("a batch holds 1 to " + OutboxLimits.MAX_BATCH_SIZE + " events");
        }
        StringBuilder body = new StringBuilder("{\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(events.get(i).payload());
        }
        return body.append("]}").toString();
    }

    static AuditApiResponse response(int status, String body, String retryAfter) {
        if (status == 200) {
            return recorded(body);
        }
        return new AuditApiResponse.Failed(status, problemType(body), seconds(retryAfter));
    }

    static AuditApiResponse unreachable(String step, Throwable error) {
        String cause = step + ": " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " " + error.getMessage());
        return new AuditApiResponse.Unreachable(cause.length() > CAUSE_LENGTH ? cause.substring(0, CAUSE_LENGTH) : cause);
    }

    /** The results the API listed; an entry without a valid {@code event_id} or {@code status} is skipped (EC-05). */
    private static AuditApiResponse recorded(String body) {
        JsonNode results = readTree(body).map(tree -> tree.get("results")).orElse(null);
        if (results == null || !results.isArray()) {
            return new AuditApiResponse.Failed(200, AuditApiResponse.Failed.INVALID_RESPONSE, null);
        }
        List<EventResult> parsed = new ArrayList<>();
        for (JsonNode result : results) {
            Optional<UUID> eventId = uuid(result.path("event_id").asText(null));
            Optional<EventStatus> status = EventStatus.fromWire(result.path("status").asText(null));
            if (eventId.isPresent() && status.isPresent()) {
                parsed.add(new EventResult(eventId.get(), status.get(), result.path("error").path("type").asText(null)));
            }
        }
        return new AuditApiResponse.Recorded(parsed);
    }

    private static String problemType(String body) {
        return readTree(body).map(tree -> tree.path("type").asText(null)).orElse(null);
    }

    private static Optional<JsonNode> readTree(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readTree(body));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> uuid(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code Retry-After} as a number of seconds (the OpenAPI declares an integer); anything else is ignored. */
    private static Duration seconds(String retryAfter) {
        if (retryAfter == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
