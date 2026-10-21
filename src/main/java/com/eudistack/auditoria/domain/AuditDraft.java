package com.eudistack.auditoria.domain;

/**
 * A fact with its result: what {@code stage} receives. {@code reason} is the catalogue reason of a
 * {@code Denied} or {@code Failure} (required there, DT-002 §3.3 step 7) and absent on {@code Success}.
 */
public record AuditDraft(AuditFact fact, AuditResult result, String reason) {

    public AuditDraft {
        Require.present(fact, "fact");
        Require.present(result, "result");
        if (result == AuditResult.SUCCESS && reason != null) {
            throw new IllegalArgumentException("reason must be null when result is Success");
        }
        if (result != AuditResult.SUCCESS) {
            Require.text(reason, "reason of a " + result.wireValue() + " result");
        }
    }

    public static AuditDraft success(AuditFact fact) {
        return new AuditDraft(fact, AuditResult.SUCCESS, null);
    }

    public static AuditDraft denied(AuditFact fact, String reason) {
        return new AuditDraft(fact, AuditResult.DENIED, reason);
    }

    public static AuditDraft failure(AuditFact fact, String reason) {
        return new AuditDraft(fact, AuditResult.FAILURE, reason);
    }
}
