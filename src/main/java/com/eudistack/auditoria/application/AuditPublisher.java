package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;

/**
 * Stages an audit event in the outbox, servlet variant (JDBC or JPA producers). DT-002 §3.3.
 *
 * <p>Call it inside the local transaction that writes the business change: the row is committed or rolled back
 * with it, and the dispatcher sends it after the commit. Each call is one event with its own {@code event_id}.
 * With {@code auditoria.publisher.enabled=false} it does nothing and returns normally.
 *
 * <p>A {@code Denied} or {@code Failure} whose business transaction rolls back is staged by the caller in a
 * short transaction of its own, after the rollback (docs/decisions.md, D-S8). A {@code Success} is always
 * staged in the business transaction.
 */
public interface AuditPublisher {

    /**
     * @throws IllegalStateException when there is no active local transaction (EC-09) or no audit context
     * @throws com.eudistack.auditoria.domain.AuditTenantMismatchException when the fact names another tenant (EC-08)
     * @throws com.eudistack.auditoria.domain.AuditDraftRejectedException when the event would carry a secret (RQ-10)
     */
    void stage(AuditDraft draft);
}
