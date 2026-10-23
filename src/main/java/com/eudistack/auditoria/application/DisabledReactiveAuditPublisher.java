package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditDraft;
import reactor.core.publisher.Mono;

/**
 * {@link ReactiveAuditPublisher} of a producer whose {@code auditoria.publisher.enabled} is false (DT-002 AD-8):
 * {@code stage} completes empty and needs no transaction, context nor table (docs/decisions.md, D-S28).
 */
public final class DisabledReactiveAuditPublisher implements ReactiveAuditPublisher {

    @Override
    public Mono<Void> stage(AuditDraft draft) {
        return Mono.empty();
    }
}
