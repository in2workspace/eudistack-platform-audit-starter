package com.eudistack.auditoria.domain;

import java.util.UUID;

/**
 * A row to insert in {@code public.audit_outbox}: the serialized event plus the columns the dispatcher needs
 * without parsing it. {@code payload} is the {@code AuditEventInput} JSON, sent to the Audit API as it is;
 * {@code traceparent} travels as a header, never inside the payload (AD-7).
 *
 * <p>The lengths of the columns are checked here, so a value that does not fit fails with a clear message
 * instead of a database error. Either way the business transaction rolls back (EC-10).
 */
public record OutboxEntry(
        UUID eventId,
        String tenant,
        String eventType,
        String correlationId,
        String payload,
        String traceparent
) {

    public OutboxEntry {
        Require.present(eventId, "event_id");
        fits(Require.text(tenant, "tenant"), OutboxLimits.TENANT_LENGTH, "tenant");
        fits(Require.text(eventType, "event_type"), OutboxLimits.EVENT_TYPE_LENGTH, "event_type");
        fits(Require.text(correlationId, "correlation_id"), OutboxLimits.CORRELATION_ID_LENGTH, "correlation_id");
        Require.text(payload, "payload");
        if (traceparent != null) {
            fits(traceparent, OutboxLimits.TRACEPARENT_LENGTH, "traceparent");
        }
    }

    /** The row of {@code event}, already serialized as {@code payload}. */
    public static OutboxEntry of(AuditEvent event, String payload, String traceparent) {
        return new OutboxEntry(event.eventId(), event.tenant(), event.eventType(), event.correlationId(), payload,
                traceparent);
    }

    private static void fits(String value, int length, String name) {
        if (value.length() > length) {
            throw new IllegalArgumentException(name + " is longer than " + length + " characters");
        }
    }
}
