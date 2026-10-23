package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.port.AuditContextAccessor;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.port.OutboxWakeup;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/**
 * {@link AuditPublisher} of servlet producers (DT-002 §3.3): stages the event in the outbox, inside the local
 * transaction of the caller, and wakes the dispatcher up once that transaction has committed.
 */
public final class OutboxAuditPublisher implements AuditPublisher {

    static final String NO_TRANSACTION = "stage needs an active local transaction: call it inside the "
            + "@Transactional method or TransactionTemplate that writes the business change (EC-09)";
    static final String NO_CONTEXT = "stage needs an audit context: a request of a producer with an "
            + "AuditContextResolver, or a scope opened with AuditContexts";

    private final AuditStaging staging;
    private final OutboxStore store;
    private final AuditContextAccessor contexts;
    private final OutboxWakeup wakeup;

    public OutboxAuditPublisher(AuditStaging staging, OutboxStore store, AuditContextAccessor contexts,
                                OutboxWakeup wakeup) {
        this.staging = staging;
        this.store = store;
        this.contexts = contexts;
        this.wakeup = wakeup;
    }

    @Override
    public void stage(AuditDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(NO_TRANSACTION);
        }
        AuditContext context = contexts.current().orElseThrow(() -> new IllegalStateException(NO_CONTEXT));
        store.insert(staging.prepare(draft, context));
        wakeUpAfterCommit();
    }

    /** One signal per transaction, however many events it stages; none if it rolls back. */
    private void wakeUpAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        boolean registered = TransactionSynchronizationManager.getSynchronizations().stream()
                .anyMatch(WakeUpAfterCommit.class::isInstance);
        if (!registered) {
            TransactionSynchronizationManager.registerSynchronization(new WakeUpAfterCommit(wakeup));
        }
    }

    private record WakeUpAfterCommit(OutboxWakeup wakeup) implements TransactionSynchronization {

        @Override
        public void afterCommit() {
            wakeup.signal();
        }
    }
}
