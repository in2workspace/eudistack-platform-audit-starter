package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;
import reactor.core.publisher.Mono;

/**
 * Stages an audit event in the outbox, reactive variant (R2DBC producers). DT-002 §3.3.
 *
 * <p>Compose the returned {@code Mono} inside the {@code TransactionalOperator} that writes the business change:
 * <pre>{@code
 * return transactionalOperator.transactional(
 *         repository.save(issuance)
 *                 .flatMap(saved -> auditPublisher.stage(AuditDraft.success(issuanceCompleted(saved)))
 *                         .thenReturn(saved)));
 * }</pre>
 * Each call is one event with its own {@code event_id}. With {@code auditoria.publisher.enabled=false} it
 * completes empty without inserting.
 *
 * <p>A {@code Denied} or {@code Failure} whose business transaction rolls back is staged by the caller in a
 * short transaction of its own, after the rollback (docs/decisions.md, D-S8). A {@code Success} is always
 * staged in the business transaction.
 */
public interface ReactiveAuditPublisher {

    /**
     * Errors, as an error signal: {@link IllegalStateException} without an active reactive transaction (EC-09) or
     * without audit context, {@link com.eudistack.auditoria.domain.AuditTenantMismatchException} (EC-08),
     * {@link com.eudistack.auditoria.domain.AuditDraftRejectedException} (RQ-10).
     */
    Mono<Void> stage(AuditDraft draft);
}
