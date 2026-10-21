package com.eudistack.auditoria.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §3.3 step 7: {@code reason} is required when the result is not {@code Success}, and absent otherwise.
 */
class AuditDraftTest {

    private final AuditFact fact = AuditFact.builder("Revocation").action("Revoke").resource("issuance:1").build();

    @Test
    void factories_setTheResult() {
        assertThat(AuditDraft.success(fact).result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(AuditDraft.denied(fact, "PowerMissing")).extracting(AuditDraft::result, AuditDraft::reason)
                .containsExactly(AuditResult.DENIED, "PowerMissing");
        assertThat(AuditDraft.failure(fact, "StatusListUnavailable")).extracting(AuditDraft::result)
                .isEqualTo(AuditResult.FAILURE);
    }

    @Test
    void deniedOrFailure_withoutReason_isRejected() {
        assertThatThrownBy(() -> AuditDraft.denied(fact, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason of a Denied result");
        assertThatThrownBy(() -> AuditDraft.failure(fact, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason of a Failure result");
    }

    @Test
    void success_withReason_isRejected() {
        assertThatThrownBy(() -> new AuditDraft(fact, AuditResult.SUCCESS, "PowerMissing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reason must be null when result is Success");
    }
}
