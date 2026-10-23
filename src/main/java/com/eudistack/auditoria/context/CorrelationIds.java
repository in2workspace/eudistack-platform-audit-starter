package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditContext;

import java.util.UUID;

/**
 * {@code correlation_id} the starter generates when nobody gives one: for a request whose resolver does not know
 * it (docs/decisions.md, D-S6), or for a background scope opened without one (AD-10).
 */
final class CorrelationIds {

    private CorrelationIds() {
    }

    static String generate() {
        return UUID.randomUUID().toString();
    }

    static AuditContext ensure(AuditContext context) {
        return context.correlationId() != null ? context : context.withCorrelationId(generate());
    }
}
