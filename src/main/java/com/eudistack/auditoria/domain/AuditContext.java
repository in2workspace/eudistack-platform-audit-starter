package com.eudistack.auditoria.domain;

/**
 * Who, for which tenant and within which operation an event is staged. It comes from the authenticated
 * request or, in a background process, from the record being processed (AD-10), never from the draft.
 *
 * <p>{@code correlationId} may be null when a producer's resolver does not know it: the starter then generates
 * one for the request before staging.
 */
public record AuditContext(
        String tenant,
        String organization,
        String correlationId,
        AuditActor actor
) {

    public AuditContext {
        Require.text(tenant, "tenant");
        Require.optionalText(organization, "organization");
        Require.optionalText(correlationId, "correlation_id");
        Require.present(actor, "actor");
    }

    /** Same context within another operation (DT-002 §3.9.1: an issuance correlates by its process id). */
    public AuditContext withCorrelationId(String correlationId) {
        return new AuditContext(tenant, organization, Require.text(correlationId, "correlation_id"), actor);
    }
}
