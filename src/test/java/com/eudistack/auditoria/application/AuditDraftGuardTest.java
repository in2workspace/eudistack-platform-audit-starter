package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.ActorKind;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditDraftRejectedException;
import com.eudistack.auditoria.domain.AuditEvent;
import com.eudistack.auditoria.domain.AuditFact;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RQ-10 and DT-002 §2.3 ({@code AuditDraftGuardTest}): no event carries a secret, whether by its key or by a
 * JWT-shaped value, and the rejection never echoes the value.
 */
class AuditDraftGuardTest {

    private static final String JWT = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJpc3N1ZXIifQ.c2lnbmF0dXJl";

    private final AuditDraftGuard guard = new AuditDraftGuard();

    @ParameterizedTest
    @ValueSource(strings = {"token", "dpop", "password", "private_key", "wia", "pre_authorized_code"})
    void secretKeysOfTheDesign_areRejected(String key) {
        AuditEvent event = event(Map.of(key, "value"), AuditActor.system("issuer"), "issuance:1");

        assertThatThrownBy(() -> guard.check(event))
                .isInstanceOf(AuditDraftRejectedException.class)
                .hasMessageContaining("details." + key + " is a secret key")
                .hasMessageNotContaining("value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"accessToken", "access-token", "ACCESS_TOKEN", "preAuthorizedCode", "Client-Secret"})
    void secretKeys_inAnyCaseOrSeparator_areRejected(String key) {
        AuditEvent event = event(Map.of(key, "value"), AuditActor.system("issuer"), "issuance:1");

        assertThatThrownBy(() -> guard.check(event)).isInstanceOf(AuditDraftRejectedException.class);
    }

    @Test
    void secretKey_nestedInAMapInsideAList_isRejectedWithItsPath() {
        AuditEvent event = event(Map.of("wallet_interaction", List.of(Map.of("protocol", "oid4vci", "dpop", "proof"))),
                AuditActor.system("issuer"), "issuance:1");

        assertThatThrownBy(() -> guard.check(event))
                .hasMessageContaining("details.wallet_interaction[0].dpop is a secret key");
    }

    @Test
    void catalogueFieldsThatOnlyContainASecretWord_areAccepted() {
        AuditEvent event = event(Map.of("secret_id", "db/issuer", "credential_configuration_id", "employee.sd.1",
                "token_type", "Bearer"), AuditActor.system("issuer"), "issuance:1");

        assertThatCode(() -> guard.check(event)).doesNotThrowAnyException();
    }

    @Test
    void jwtShapedValue_inDetails_isRejectedWithoutEchoingIt() {
        AuditEvent event = event(Map.of("holder", Map.of("identifier", JWT)), AuditActor.system("issuer"),
                "issuance:1");

        assertThatThrownBy(() -> guard.check(event))
                .hasMessageContaining("details.holder.identifier carries a JWT-shaped value")
                .hasMessageNotContaining(JWT);
    }

    @Test
    void jwtShapedValue_inTheActor_isRejected() {
        AuditActor actor = new AuditActor(ActorKind.HOLDER, JWT, null, null, null, null);

        assertThatThrownBy(() -> guard.check(event(Map.of(), actor, "issuance:1")))
                .hasMessageContaining("actor.id carries a JWT-shaped value");
    }

    @Test
    void jwtShapedValue_inTheResource_isRejected() {
        assertThatThrownBy(() -> guard.check(event(Map.of(), AuditActor.system("issuer"), JWT)))
                .hasMessageContaining("resource carries a JWT-shaped value");
    }

    @Test
    void ordinaryEvent_isAccepted() {
        AuditEvent event = event(Map.of(
                        "holder", Map.of("identifier", "12345678Z", "name", "María García López"),
                        "signing_certificate", "sha256:9f2c",
                        "issued_attributes", List.of("given_name", "family_name"),
                        "accepted_by_holder", true),
                AuditActor.person("12345678Z", "María García López", "maria@example.org"), "issuance:op-1");

        assertThatCode(() -> guard.check(event)).doesNotThrowAnyException();
    }

    private static AuditEvent event(Map<String, ?> details, AuditActor actor, String resource) {
        AuditFact fact = AuditFact.builder("IssuanceCompleted").action("Issue").resource(resource)
                .details(details).build();
        AuditContext context = new AuditContext("sandbox", null, "op-1", actor);
        return AuditEvent.of(AuditDraft.success(fact), context, UUID.randomUUID(), Instant.now(), "2.2.0", "issuer");
    }
}
