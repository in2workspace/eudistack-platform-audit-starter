package com.eudistack.auditoria.domain;

/**
 * Who causes the action (OpenAPI {@code Actor}), identified in clear: the catalogue requires it.
 * {@code kind} and {@code id} are mandatory; the rest is null when unknown.
 */
public record AuditActor(
        ActorKind kind,
        String id,
        String name,
        String email,
        String organization,
        String attestationId
) {

    public AuditActor {
        Require.present(kind, "actor.kind");
        Require.text(id, "actor.id");
        Require.optionalText(name, "actor.name");
        Require.optionalText(email, "actor.email");
        Require.optionalText(organization, "actor.organization");
        Require.optionalText(attestationId, "actor.attestation_id");
    }

    /** The producing system itself, e.g. {@code system("issuer")}. */
    public static AuditActor system(String id) {
        return new AuditActor(ActorKind.SYSTEM, id, null, null, null, null);
    }

    public static AuditActor person(String id, String name, String email) {
        return new AuditActor(ActorKind.PERSON, id, name, email, null, null);
    }

    public static AuditActor holder(String id) {
        return new AuditActor(ActorKind.HOLDER, id, null, null, null, null);
    }
}
