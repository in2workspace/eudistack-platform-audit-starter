package com.eudistack.auditoria.domain;

/**
 * How a failed use case ended, in the terms of the catalogue: {@code Denied} or {@code Failure} with the reason of its
 * vocabulary (DT-002 AD-6).
 */
public record AuditOutcome(AuditResult result, String reason) {

    public AuditOutcome {
        Require.present(result, "result");
        if (result == AuditResult.SUCCESS) {
            throw new IllegalArgumentException("an outcome of a failure is Denied or Failure, never Success");
        }
        Require.text(reason, "reason");
    }

    public static AuditOutcome denied(String reason) {
        return new AuditOutcome(AuditResult.DENIED, reason);
    }

    public static AuditOutcome failure(String reason) {
        return new AuditOutcome(AuditResult.FAILURE, reason);
    }

    /** The draft of {@code fact} with this outcome. */
    public AuditDraft draft(AuditFact fact) {
        return new AuditDraft(fact, result, reason);
    }
}
