package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditFact;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@link AuditUseCase} without the proxy, servlet variant (DT-002 AD-6): for self-invocation, private or final
 * methods. Runs {@code action}, which may prepare more facts with {@link AuditUseCases#prepare}, and stages all of
 * them with the result, exactly as the annotation does. Same transaction rule: call it inside the business
 * transaction.
 */
public interface AuditUseCaseOperator {

    /** @return what {@code action} returns; its exception is rethrown as is */
    <T> T run(List<AuditFact> facts, Supplier<T> action);
}
