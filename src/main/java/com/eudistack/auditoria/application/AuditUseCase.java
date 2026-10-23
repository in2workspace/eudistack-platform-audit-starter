package com.eudistack.auditoria.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a use case whose audit result the starter classifies (DT-002 AD-6). Inside the method, prepare the facts
 * with {@link AuditUseCases#prepare} (or {@link ReactiveAuditUseCases#prepare} in a {@code Mono}); when the method
 * ends, each fact is staged as {@code Success}, or with the outcome the producer's {@code AuditOutcomeClassifier}
 * gives for its exception.
 *
 * <p>The method has to start inside the business transaction: on the same method as {@code @Transactional}, or on
 * one called from inside it; for a {@code Mono}, inside the {@code TransactionalOperator}. Otherwise it fails before
 * running. A {@code Denied} or {@code Failure} whose transaction rolls back is lost with it: stage those as in
 * docs/decisions.md D-S8. Like any Spring proxy, it does not apply to self-invocation nor to private or final
 * methods: use {@link AuditUseCaseOperator} there.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditUseCase {
}
