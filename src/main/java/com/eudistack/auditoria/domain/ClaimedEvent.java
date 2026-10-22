package com.eudistack.auditoria.domain;

import java.util.UUID;

/**
 * A row the dispatcher has claimed ({@code IN_FLIGHT}), ready to send. {@code attempts} already counts this
 * claim. {@code eventType} is there for the logs and metrics of the result, so the payload is never parsed or
 * logged.
 */
public record ClaimedEvent(
        UUID eventId,
        String tenant,
        String eventType,
        String payload,
        String traceparent,
        int attempts
) {

    public ClaimedEvent {
        Require.present(eventId, "event_id");
        Require.text(tenant, "tenant");
        Require.text(eventType, "event_type");
        Require.text(payload, "payload");
    }
}
