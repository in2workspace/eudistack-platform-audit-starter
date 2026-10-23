package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditOutcome;

import java.util.Optional;

/**
 * Translates the exceptions of a producer's use cases into catalogue outcomes (DT-002 AD-6), e.g. a power check
 * failure into {@code Denied / PowerMissing}. Each producer provides one bean; without it, no failure is classified.
 *
 * <p>Return empty for an exception whose reason is not in the vocabulary of the type: then no event is sent
 * (RQ-11, EC-12).
 */
@FunctionalInterface
public interface AuditOutcomeClassifier {

    Optional<AuditOutcome> classify(Throwable error);
}
