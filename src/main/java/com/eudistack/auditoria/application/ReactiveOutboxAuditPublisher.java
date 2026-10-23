package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.port.OutboxWakeup;
import com.eudistack.auditoria.port.ReactiveAuditContextAccessor;
import com.eudistack.auditoria.port.ReactiveOutboxStore;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveAuditPublisher} of reactive producers (DT-002 §3.3): stages the event in the outbox, inside the
 * reactive transaction of the subscriber, and wakes the dispatcher up once that transaction has committed. Never
 * blocks; every failure is an error signal that rolls the business back.
 */
public final class ReactiveOutboxAuditPublisher implements ReactiveAuditPublisher {

    static final String NO_TRANSACTION = "stage needs an active reactive transaction: compose it inside the "
            + "TransactionalOperator that writes the business change (EC-09)";
    static final String NO_CONTEXT = "stage needs an audit context: a request of a producer with a "
            + "ReactiveAuditContextResolver, or a context written with ReactorAuditContexts";

    private final AuditStaging staging;
    private final ReactiveOutboxStore store;
    private final ReactiveAuditContextAccessor contexts;
    private final OutboxWakeup wakeup;

    public ReactiveOutboxAuditPublisher(AuditStaging staging, ReactiveOutboxStore store,
                                        ReactiveAuditContextAccessor contexts, OutboxWakeup wakeup) {
        this.staging = staging;
        this.store = store;
        this.contexts = contexts;
        this.wakeup = wakeup;
    }

    @Override
    public Mono<Void> stage(AuditDraft draft) {
        if (draft == null) {
            return Mono.error(new NullPointerException("draft"));
        }
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(NoTransactionException.class, e -> new IllegalStateException(NO_TRANSACTION, e))
                .flatMap(transaction -> {
                    if (!transaction.isActualTransactionActive()) {
                        return Mono.error(new IllegalStateException(NO_TRANSACTION));
                    }
                    return contexts.current()
                            .switchIfEmpty(Mono.error(() -> new IllegalStateException(NO_CONTEXT)))
                            .map(context -> staging.prepare(draft, context))
                            .flatMap(store::insert)
                            .then(Mono.fromRunnable(() -> wakeUpAfterCommit(transaction)));
                });
    }

    /** One signal per transaction, however many events it stages; none if it rolls back. */
    private void wakeUpAfterCommit(TransactionSynchronizationManager transaction) {
        if (!transaction.isSynchronizationActive()) {
            return;
        }
        boolean registered = transaction.getSynchronizations().stream().anyMatch(WakeUpAfterCommit.class::isInstance);
        if (!registered) {
            transaction.registerSynchronization(new WakeUpAfterCommit(wakeup));
        }
    }

    private record WakeUpAfterCommit(OutboxWakeup wakeup) implements TransactionSynchronization {

        @Override
        public Mono<Void> afterCommit() {
            return Mono.fromRunnable(wakeup::signal);
        }
    }
}
