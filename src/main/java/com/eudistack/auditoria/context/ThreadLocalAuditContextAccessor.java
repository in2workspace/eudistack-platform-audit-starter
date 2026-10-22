package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.AuditContextAccessor;

import java.util.Optional;

/**
 * {@link AuditContextAccessor} of servlet producers and background threads (DT-002 §3.4.1): the innermost context
 * open on the current thread, from {@link AuditContextFilter} or from {@link AuditContexts}.
 */
public final class ThreadLocalAuditContextAccessor implements AuditContextAccessor {

    @Override
    public Optional<AuditContext> current() {
        return ThreadAuditContexts.current();
    }
}
