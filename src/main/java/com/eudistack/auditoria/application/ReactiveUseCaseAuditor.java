package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditOutcome;
import com.eudistack.auditoria.port.AuditOutcomeClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.reactive.TransactionSynchronization;
import org.springframework.transaction.reactive.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Runs a reactive use case and stages its facts with its result (DT-002 AD-6, docs/decisions.md D-S32): the engine
 * of {@link AuditUseCase} on {@code Mono} methods and of {@link ReactiveAuditUseCaseOperator}. Same rules as the
 * servlet variant, as signals: it never opens a transaction, and with the publisher off it only runs the action.
 */
@Slf4j
public final class ReactiveUseCaseAuditor implements ReactiveAuditUseCaseOperator {

    static final String NO_TRANSACTION = "an audited use case has to run inside the business TransactionalOperator: "
            + "it stages there (EC-09)";

    private final ReactiveAuditPublisher publisher;
    private final AuditOutcomeClassifier classifier;
    private final boolean enabled;

    public ReactiveUseCaseAuditor(ReactiveAuditPublisher publisher, AuditOutcomeClassifier classifier) {
        this.publisher = publisher;
        this.classifier = classifier;
        this.enabled = !(publisher instanceof DisabledReactiveAuditPublisher);
    }

    @Override
    public <T> Mono<T> run(List<AuditFact> facts, Mono<T> action) {
        return audit("ReactiveAuditUseCaseOperator.run", facts, action);
    }

    <T> Mono<T> audit(String useCase, List<AuditFact> initial, Mono<T> body) {
        return Mono.defer(() -> {
            ReactiveAuditUseCases.Pending pending = ReactiveAuditUseCases.start(initial);
            Mono<T> audited = enabled
                    ? requireTransaction(useCase).then(body
                            .onErrorResume(error -> stageFailure(useCase, pending.facts(), error).then(Mono.error(error)))
                            .flatMap(value -> stageSuccess(useCase, pending.facts()).thenReturn(value))
                            .switchIfEmpty(Mono.defer(() -> stageSuccess(useCase, pending.facts()).then(Mono.empty()))))
                    : body;
            return audited.contextWrite(pending::writeTo);
        });
    }

    private static Mono<Void> requireTransaction(String useCase) {
        IllegalStateException noTransaction = new IllegalStateException(useCase + ": " + NO_TRANSACTION);
        return TransactionSynchronizationManager.forCurrentTransaction()
                .onErrorMap(NoTransactionException.class, e -> noTransaction)
                .flatMap(transaction -> transaction.isActualTransactionActive()
                        ? Mono.<Void>empty()
                        : Mono.error(noTransaction));
    }

    private Mono<Void> stageSuccess(String useCase, List<AuditFact> facts) {
        if (facts.isEmpty()) {
            log.warn("{} ended without a prepared fact: no audit event (audit_outbox_missing_draft)", useCase);
            return Mono.empty();
        }
        return Flux.fromIterable(List.copyOf(facts)).concatMap(fact -> publisher.stage(AuditDraft.success(fact))).then();
    }

    private Mono<Void> stageFailure(String useCase, List<AuditFact> facts, Throwable error) {
        if (facts.isEmpty()) {
            return Mono.empty();
        }
        Optional<AuditOutcome> outcome = classifier.classify(error);
        if (outcome.isEmpty()) {
            log.debug("{} failed with {}, which has no catalogue reason: no audit event (RQ-11)", useCase,
                    error.getClass().getName());
            return Mono.empty();
        }
        List<AuditFact> staged = List.copyOf(facts);
        return Flux.fromIterable(staged)
                .concatMap(fact -> publisher.stage(outcome.get().draft(fact)))
                .then(watchForRollback(useCase, staged, outcome.get()))
                .onErrorResume(stagingError -> {
                    error.addSuppressed(stagingError);
                    return Mono.empty();
                });
    }

    /** A failure staged in a transaction that then rolls back is lost: say so, once per transaction. */
    private static Mono<Void> watchForRollback(String useCase, List<AuditFact> facts, AuditOutcome outcome) {
        return TransactionSynchronizationManager.forCurrentTransaction()
                .doOnNext(transaction -> {
                    if (transaction.isSynchronizationActive()) {
                        transaction.registerSynchronization(new RollbackWatch(useCase, facts, outcome));
                    }
                })
                .then();
    }

    private record RollbackWatch(String useCase, List<AuditFact> facts, AuditOutcome outcome)
            implements TransactionSynchronization {

        @Override
        public Mono<Void> afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
                log.warn("{}: {} {} event(s) {} lost with the rollback of the business transaction; stage them in "
                                + "a short transaction after the rollback (docs/decisions.md, D-S8)", useCase,
                        facts.size(), outcome.result().wireValue(),
                        facts.stream().map(AuditFact::eventType).distinct().toList());
            }
            return Mono.empty();
        }
    }
}
