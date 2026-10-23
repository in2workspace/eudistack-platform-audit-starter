package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditContext;
import reactor.core.publisher.Mono;

/**
 * The audit context of the current subscription, reactive variant (DT-002 §3.3, RQ-15): read from the Reactor
 * Context, where the request filter or an explicit background scope (AD-10) left it.
 */
@FunctionalInterface
public interface ReactiveAuditContextAccessor {

    /** The current context, with a {@code correlationId}; empty when there is none. */
    Mono<AuditContext> current();
}
