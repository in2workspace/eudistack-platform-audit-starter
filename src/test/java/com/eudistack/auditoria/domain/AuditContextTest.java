package com.eudistack.auditoria.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The context and its actor require what the catalogue requires ({@code tenant}, {@code actor.kind},
 * {@code actor.id}); {@code correlation_id} may wait until the starter or the use case sets it.
 */
class AuditContextTest {

    @Test
    void withoutTenantOrActor_isRejected() {
        assertThatThrownBy(() -> new AuditContext(" ", null, "op-1", AuditActor.system("issuer")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant");
        assertThatThrownBy(() -> new AuditContext("sandbox", null, "op-1", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actor");
    }

    @Test
    void withCorrelationId_replacesOnlyTheCorrelation() {
        AuditContext context = new AuditContext("sandbox", "VATES-B00000002", null, AuditActor.system("verifier"));

        AuditContext correlated = context.withCorrelationId("issuance-42");

        assertThat(correlated).isEqualTo(
                new AuditContext("sandbox", "VATES-B00000002", "issuance-42", AuditActor.system("verifier")));
        assertThatThrownBy(() -> context.withCorrelationId(null)).hasMessageContaining("correlation_id");
    }

    @Test
    void actor_requiresKindAndId() {
        assertThatThrownBy(() -> new AuditActor(null, "issuer", null, null, null, null))
                .hasMessageContaining("actor.kind");
        assertThatThrownBy(() -> AuditActor.system("")).hasMessageContaining("actor.id");
    }

    @Test
    void actorFactories_setTheKind() {
        assertThat(AuditActor.system("issuer").kind()).isEqualTo(ActorKind.SYSTEM);
        assertThat(AuditActor.person("12345678Z", "María García", "maria@example.org"))
                .extracting(AuditActor::kind, AuditActor::name, AuditActor::email)
                .containsExactly(ActorKind.PERSON, "María García", "maria@example.org");
        assertThat(AuditActor.holder("did:key:z6Mk").kind()).isEqualTo(ActorKind.HOLDER);
    }
}
