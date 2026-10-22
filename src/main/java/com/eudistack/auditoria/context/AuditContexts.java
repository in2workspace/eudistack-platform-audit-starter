package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;

import java.util.Optional;

/**
 * Audit contexts opened explicitly on the current thread, for servlet producers and background processes
 * (DT-002 AD-10). Use them with try-with-resources:
 * <pre>{@code
 * try (AuditContextScope scope = AuditContexts.system(process.tenant(), "issuer")) {
 *     withdrawalService.withdraw(process);   // @Transactional, stages inside
 * }
 * }</pre>
 * In a request, the context comes from the producer's {@code AuditContextResolver}; nothing has to be opened.
 * Reactive producers use {@link ReactorAuditContexts}.
 */
public final class AuditContexts {

    private AuditContexts() {
    }

    /**
     * A process without a request acting on {@code tenant}: the actor is the producer itself
     * ({@code kind: System}) and a new {@code correlation_id} is created.
     *
     * @param tenant  the tenant of the record being processed, never one from configuration (AD-10)
     * @param actorId the producing system, e.g. {@code issuer}
     */
    public static AuditContextScope system(String tenant, String actorId) {
        return system(tenant, actorId, null);
    }

    /** As {@link #system(String, String)}, within the operation {@code correlationId} when the process has it. */
    public static AuditContextScope system(String tenant, String actorId, String correlationId) {
        AuditContext context = new AuditContext(tenant, null, correlationId, AuditActor.system(actorId));
        return ThreadAuditContexts.open(() -> Optional.of(context));
    }

    /** A given context, e.g. in a test or a process that knows its actor. */
    public static AuditContextScope open(AuditContext context) {
        return ThreadAuditContexts.open(() -> Optional.of(context));
    }

    /**
     * Changes only the {@code correlation_id} of the current context until the scope is closed, e.g. to the
     * process id of an issuance (DT-002 §3.9.1). With no current context, there is still none.
     */
    public static AuditContextScope withCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlation_id is required");
        }
        return ThreadAuditContexts.derive(context -> context.withCorrelationId(correlationId));
    }
}
