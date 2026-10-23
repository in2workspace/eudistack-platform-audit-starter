package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;

/**
 * {@link AuditPublisher} of a producer whose {@code auditoria.publisher.enabled} is false (DT-002 AD-8): {@code stage}
 * does nothing and needs no transaction, context nor table, so the producer runs as if the starter were absent
 * (docs/decisions.md, D-S28).
 */
public final class DisabledAuditPublisher implements AuditPublisher {

    @Override
    public void stage(AuditDraft draft) {
        // Publisher off: nothing is staged.
    }
}
