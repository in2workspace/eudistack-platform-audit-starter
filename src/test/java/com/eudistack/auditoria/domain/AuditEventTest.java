package com.eudistack.auditoria.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §3.3 steps 2 and 3 and the field table: tenant, organization, actor and {@code correlation_id} come from
 * the context, {@code source} from the fact or the configuration, and a draft of another tenant fails (EC-08).
 */
class AuditEventTest {

    private static final UUID EVENT_ID = UUID.fromString("5d1c7a40-2f0e-4b8a-9a61-0c3e5f7d2b11");
    private static final Instant NOW = Instant.parse("2026-10-21T09:12:44.123456789Z");

    private final AuditContext context = new AuditContext("red-logistica", "VATES-B00000000", "op-1",
            AuditActor.system("issuer"));

    @Test
    void of_takesTheContextAndTheDraft() {
        AuditFact fact = AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:op-1")
                .detail("attestation_type", "employee.sd.1").build();

        AuditEvent event = AuditEvent.of(AuditDraft.success(fact), context, EVENT_ID, NOW, "2.2.0", "issuer");

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.eventType()).isEqualTo("IssuanceCompleted");
        assertThat(event.eventVersion()).isEqualTo("2.2.0");
        assertThat(event.tenant()).isEqualTo("red-logistica");
        assertThat(event.organization()).isEqualTo("VATES-B00000000");
        assertThat(event.source()).isEqualTo("issuer");
        assertThat(event.actor()).isEqualTo(AuditActor.system("issuer"));
        assertThat(event.action()).isEqualTo("Issue");
        assertThat(event.resource()).isEqualTo("issuance:op-1");
        assertThat(event.result()).isEqualTo(AuditResult.SUCCESS);
        assertThat(event.reason()).isNull();
        assertThat(event.correlationId()).isEqualTo("op-1");
        assertThat(event.details()).containsEntry("attestation_type", "employee.sd.1");
    }

    @Test
    void occurredAt_isTruncatedToMilliseconds() {
        assertThat(event(fact().build()).occurredAt()).isEqualTo(Instant.parse("2026-10-21T09:12:44.123Z"));
    }

    @Test
    void sourceOfTheFact_overridesTheDefault() {
        assertThat(event(fact().source("status-lists").build()).source()).isEqualTo("status-lists");
    }

    @Test
    void noSourceAnywhere_isRejected() {
        AuditDraft draft = AuditDraft.success(fact().build());

        assertThatThrownBy(() -> AuditEvent.of(draft, context, EVENT_ID, NOW, "2.2.0", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("auditoria.source");
    }

    @Test
    void tenantOfTheFact_matchingTheContext_isAccepted() {
        assertThat(event(fact().tenant("red-logistica").build()).tenant()).isEqualTo("red-logistica");
    }

    @Test
    void tenantOfTheFact_otherThanTheContext_fails() {
        AuditDraft draft = AuditDraft.success(fact().tenant("sandbox").build());

        assertThatThrownBy(() -> AuditEvent.of(draft, context, EVENT_ID, NOW, "2.2.0", "issuer"))
                .isInstanceOf(AuditTenantMismatchException.class)
                .hasMessageContaining("'sandbox'").hasMessageContaining("'red-logistica'").hasMessageContaining("EC-08");
    }

    @Test
    void contextWithoutCorrelationId_fails() {
        AuditContext withoutCorrelation = new AuditContext("red-logistica", null, null, AuditActor.system("issuer"));
        AuditDraft draft = AuditDraft.success(fact().build());

        assertThatThrownBy(() -> AuditEvent.of(draft, withoutCorrelation, EVENT_ID, NOW, "2.2.0", "issuer"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("correlation_id");
    }

    @Test
    void reasonOfTheDraft_isKept() {
        AuditEvent event = AuditEvent.of(AuditDraft.denied(fact().build(), "PowerMissing"), context, EVENT_ID, NOW,
                "2.2.0", "issuer");

        assertThat(event.result()).isEqualTo(AuditResult.DENIED);
        assertThat(event.reason()).isEqualTo("PowerMissing");
    }

    private AuditEvent event(AuditFact fact) {
        return AuditEvent.of(AuditDraft.success(fact), context, EVENT_ID, NOW, "2.2.0", "issuer");
    }

    private static AuditFact.Builder fact() {
        return AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:op-1");
    }
}
