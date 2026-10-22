package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.ReactiveAuditContextAccessor;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveAuditContextAccessor} of reactive producers (DT-002 §3.4.1): the innermost context in the Reactor
 * Context of the subscriber, from {@link AuditContextWebFilter} or from {@link ReactorAuditContexts}.
 */
public final class ReactorAuditContextAccessor implements ReactiveAuditContextAccessor {

    @Override
    public Mono<AuditContext> current() {
        return Mono.deferContextual(ReactorAuditContexts::current);
    }
}
