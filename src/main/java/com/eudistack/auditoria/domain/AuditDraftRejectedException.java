package com.eudistack.auditoria.domain;

/**
 * The event would carry a secret (RQ-10): a key such as {@code token} or {@code password}, or a value shaped
 * like a JWT. The message names the event type and the path, never the value.
 */
public final class AuditDraftRejectedException extends AuditoriaException {

    public AuditDraftRejectedException(String eventType, String problem) {
        super("Audit event " + eventType + " rejected: " + problem + " (RQ-10)");
    }
}
