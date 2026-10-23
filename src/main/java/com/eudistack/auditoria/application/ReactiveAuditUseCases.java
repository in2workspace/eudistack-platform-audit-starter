package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditFact;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The facts a running reactive {@link AuditUseCase} has prepared, carried in the Reactor Context (DT-002 AD-6). A
 * fact goes to the innermost use case of the subscription.
 */
public final class ReactiveAuditUseCases {

    private static final Object KEY = ReactiveAuditUseCases.class;

    private ReactiveAuditUseCases() {
    }

    /**
     * Adds {@code fact} to the running use case, as a step of the {@code Mono}:
     * {@code revoke(request).then(ReactiveAuditUseCases.prepare(fact))}. Error signal when no use case is running.
     */
    public static Mono<Void> prepare(AuditFact fact) {
        Objects.requireNonNull(fact, "fact");
        return Mono.deferContextual(context -> context.getOrEmpty(KEY)
                .filter(Pending.class::isInstance)
                .map(pending -> {
                    ((Pending) pending).facts().add(fact);
                    return Mono.<Void>empty();
                })
                .orElseGet(() -> Mono.error(new IllegalStateException(AuditUseCases.NO_USE_CASE))));
    }

    /** A new use case with {@code initial} facts, to write in the Reactor Context of its subscription. */
    static Pending start(List<AuditFact> initial) {
        return new Pending(new CopyOnWriteArrayList<>(initial));
    }

    record Pending(List<AuditFact> facts) {

        Context writeTo(Context context) {
            return context.put(KEY, this);
        }
    }
}
