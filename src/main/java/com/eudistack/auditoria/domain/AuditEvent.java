package com.eudistack.auditoria.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * The canonical record of an event, as staged and sent to the Audit API (OpenAPI {@code AuditEventInput}).
 * Built once, at {@code stage}; the dispatcher sends it as it was stored and never rebuilds it.
 * {@code recorded_at} is not here: the Audit API sets it.
 */
public record AuditEvent(
        UUID eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String tenant,
        String organization,
        String source,
        AuditActor actor,
        String action,
        String resource,
        AuditResult result,
        String reason,
        String correlationId,
        Map<String, Object> details
) {

    public AuditEvent {
        Require.present(eventId, "event_id");
        Require.text(eventType, "event_type");
        Require.text(eventVersion, "event_version");
        Require.present(occurredAt, "occurred_at");
        Require.text(tenant, "tenant");
        Require.optionalText(organization, "organization");
        Require.text(source, "source");
        Require.present(actor, "actor");
        Require.text(action, "action");
        Require.text(resource, "resource");
        Require.present(result, "result");
        Require.text(correlationId, "correlation_id");
        details = AuditDetails.normalize(details);
    }

    /**
     * Builds the event of {@code draft} within {@code context} (DT-002 §3.3 steps 2 and 3): tenant, organization,
     * actor and {@code correlation_id} come from the context, never from the draft.
     *
     * @param eventId       generated once by the caller; the same UUID goes to the outbox row and is never
     *                      regenerated on resend
     * @param occurredAt    instant of the fact, truncated to milliseconds
     * @param eventVersion  catalogue version ({@code auditoria.catalog-version})
     * @param defaultSource {@code auditoria.source}, used when the fact does not name its own
     * @throws AuditTenantMismatchException when the fact names another tenant (EC-08)
     */
    public static AuditEvent of(AuditDraft draft, AuditContext context, UUID eventId, Instant occurredAt,
                                String eventVersion, String defaultSource) {
        AuditFact fact = draft.fact();
        if (fact.tenant() != null && !fact.tenant().equals(context.tenant())) {
            throw new AuditTenantMismatchException(fact.tenant(), context.tenant());
        }
        if (context.correlationId() == null) {
            throw new IllegalStateException("The audit context has no correlation_id");
        }
        String source = fact.source() != null ? fact.source() : defaultSource;
        return new AuditEvent(
                eventId,
                fact.eventType(),
                eventVersion,
                Require.present(occurredAt, "occurred_at").truncatedTo(ChronoUnit.MILLIS),
                context.tenant(),
                context.organization(),
                Require.text(source, "source (the fact's or auditoria.source)"),
                context.actor(),
                fact.action(),
                fact.resource(),
                draft.result(),
                draft.reason(),
                context.correlationId(),
                fact.details());
    }
}
