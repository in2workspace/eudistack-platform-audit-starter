package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.function.Function;

/**
 * Audit contexts written explicitly in the Reactor Context, for reactive producers and background processes
 * (DT-002 AD-10). Apply them with {@code contextWrite} on the {@code Mono} that stages:
 * <pre>{@code
 * return withdraw(process)
 *         .contextWrite(ReactorAuditContexts.system(process.tenant(), "issuer"));
 * }</pre>
 * In a request, the context comes from the producer's {@code ReactiveAuditContextResolver}; nothing has to be
 * written. Servlet producers use {@link AuditContexts}.
 */
public final class ReactorAuditContexts {

    private static final Object KEY = ReactorAuditContexts.class;

    private ReactorAuditContexts() {
    }

    /**
     * A process without a request acting on {@code tenant}: the actor is the producer itself
     * ({@code kind: System}) and a new {@code correlation_id} is created for each subscription.
     *
     * @param tenant  the tenant of the record being processed, never one from configuration (AD-10)
     * @param actorId the producing system, e.g. {@code issuer}
     */
    public static Function<Context, Context> system(String tenant, String actorId) {
        return system(tenant, actorId, null);
    }

    /** As {@link #system(String, String)}, within the operation {@code correlationId} when the process has it. */
    public static Function<Context, Context> system(String tenant, String actorId, String correlationId) {
        return with(new AuditContext(tenant, null, correlationId, AuditActor.system(actorId)));
    }

    /** A given context, e.g. in a test or a process that knows its actor. */
    public static Function<Context, Context> with(AuditContext context) {
        return reactorContext -> reactorContext.put(KEY, new Frame(Mono.just(CorrelationIds.ensure(context))));
    }

    /**
     * Changes only the {@code correlation_id} of the context below, e.g. to the process id of an issuance
     * (DT-002 §3.9.1). With no context below, there is still none.
     */
    public static Function<Context, Context> withCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlation_id is required");
        }
        return reactorContext -> reactorContext.put(KEY,
                new Frame(current(reactorContext).map(context -> context.withCorrelationId(correlationId))));
    }

    /** The context of a request, resolved lazily and once by {@code resolution}. */
    static Function<Context, Context> lazy(Mono<AuditContext> resolution) {
        return reactorContext -> reactorContext.put(KEY, new Frame(resolution));
    }

    static Mono<AuditContext> current(ContextView reactorContext) {
        return reactorContext.getOrEmpty(KEY)
                .filter(Frame.class::isInstance)
                .map(frame -> ((Frame) frame).context())
                .orElse(Mono.empty());
    }

    private record Frame(Mono<AuditContext> context) {
    }
}
