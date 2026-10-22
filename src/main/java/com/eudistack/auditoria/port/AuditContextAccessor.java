package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditContext;

import java.util.Optional;

/**
 * The audit context of the current thread, servlet variant (DT-002 §3.3, RQ-15): the one of the request, resolved
 * by the producer's {@link AuditContextResolver}, or the one a background process opened explicitly (AD-10).
 */
@FunctionalInterface
public interface AuditContextAccessor {

    /** The current context, with a {@code correlationId}; empty when there is none. */
    Optional<AuditContext> current();
}
