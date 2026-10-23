package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditFact;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * {@link AuditUseCase} without the proxy, reactive variant (DT-002 AD-6), the usual tool in a {@code Mono}. The
 * returned {@code Mono} must run inside the business {@code TransactionalOperator}:
 * <pre>{@code
 * return transactionalOperator.transactional(
 *         useCases.run(List.of(revocationFact(request)), revoke(request)));
 * }</pre>
 * {@code action} may prepare more facts with {@link ReactiveAuditUseCases#prepare}.
 */
public interface ReactiveAuditUseCaseOperator {

    /** @return {@code action}, with its facts staged when it completes; its error is propagated as is */
    <T> Mono<T> run(List<AuditFact> facts, Mono<T> action);
}
