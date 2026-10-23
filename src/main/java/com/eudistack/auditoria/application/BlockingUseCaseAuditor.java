package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditOutcome;
import com.eudistack.auditoria.port.AuditOutcomeClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Runs a use case and stages its facts with its result, servlet variant (DT-002 AD-6, docs/decisions.md D-S32): the
 * engine of {@link AuditUseCase} and of {@link AuditUseCaseOperator}.
 *
 * <p>It stages inside the business transaction and never opens one (EC-09): a use case has to start inside it. With
 * the publisher off it only runs the action, so the producer behaves as without the starter.
 */
@Slf4j
public final class BlockingUseCaseAuditor implements AuditUseCaseOperator {

    static final String NO_TRANSACTION = "an audited use case has to start inside the business transaction "
            + "(@Transactional on the same method or on its caller, or a TransactionTemplate): it stages there (EC-09)";

    private final AuditPublisher publisher;
    private final AuditOutcomeClassifier classifier;
    private final boolean enabled;

    public BlockingUseCaseAuditor(AuditPublisher publisher, AuditOutcomeClassifier classifier) {
        this.publisher = publisher;
        this.classifier = classifier;
        this.enabled = !(publisher instanceof DisabledAuditPublisher);
    }

    @Override
    public <T> T run(List<AuditFact> facts, Supplier<T> action) {
        try {
            return audit("AuditUseCaseOperator.run", facts, action::get);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable unreachable) {
            // A Supplier cannot throw a checked exception.
            throw new IllegalStateException(unreachable);
        }
    }

    <T> T audit(String useCase, List<AuditFact> initial, Body<T> body) throws Throwable {
        if (enabled && !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(useCase + ": " + NO_TRANSACTION);
        }
        List<AuditFact> facts;
        T result;
        try (AuditUseCases.Running running = AuditUseCases.start(initial)) {
            facts = running.facts();
            try {
                result = body.run();
            } catch (Throwable error) {
                if (enabled) {
                    stageFailure(useCase, List.copyOf(facts), error);
                }
                throw error;
            }
        }
        if (enabled) {
            stageSuccess(useCase, List.copyOf(facts));
        }
        return result;
    }

    private void stageSuccess(String useCase, List<AuditFact> facts) {
        if (facts.isEmpty()) {
            log.warn("{} ended without a prepared fact: no audit event (audit_outbox_missing_draft)", useCase);
            return;
        }
        facts.forEach(fact -> publisher.stage(AuditDraft.success(fact)));
    }

    private void stageFailure(String useCase, List<AuditFact> facts, Throwable error) {
        if (facts.isEmpty()) {
            return;
        }
        Optional<AuditOutcome> outcome = classifier.classify(error);
        if (outcome.isEmpty()) {
            log.debug("{} failed with {}, which has no catalogue reason: no audit event (RQ-11)", useCase,
                    error.getClass().getName());
            return;
        }
        try {
            facts.forEach(fact -> publisher.stage(outcome.get().draft(fact)));
            watchForRollback(useCase, facts, outcome.get());
        } catch (RuntimeException stagingError) {
            error.addSuppressed(stagingError);
        }
    }

    /** A failure staged in a transaction that then rolls back is lost: say so, once per transaction. */
    private static void watchForRollback(String useCase, List<AuditFact> facts, AuditOutcome outcome) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new RollbackWatch(useCase, facts, outcome));
        }
    }

    @FunctionalInterface
    interface Body<T> {
        T run() throws Throwable;
    }

    private record RollbackWatch(String useCase, List<AuditFact> facts, AuditOutcome outcome)
            implements TransactionSynchronization {

        @Override
        public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
                log.warn("{}: {} {} event(s) {} lost with the rollback of the business transaction; stage them in "
                                + "a short transaction after the rollback (docs/decisions.md, D-S8)", useCase,
                        facts.size(), outcome.result().wireValue(),
                        facts.stream().map(AuditFact::eventType).distinct().toList());
            }
        }
    }
}
