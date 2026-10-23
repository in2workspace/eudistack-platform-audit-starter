package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.ActorKind;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditEvent;
import com.eudistack.auditoria.domain.AuditFact;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §3.3 step 4: the payload is the OpenAPI {@code AuditEventInput} of the Audit API, validated as JSON Schema
 * 2020-12 against {@code src/test/resources/contract/openapi.yaml}, a copy of {@code docs/openapi.yaml} of
 * {@code eudistack-core-audit} (identical to {@code annex/openapi.yaml} of EUD-244). Same validation as the
 * API's {@code OpenApiContractIT}, so both sides check the contract alike.
 */
class AuditEventJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode openApi;

    private final AuditEventJson writer = new AuditEventJson();

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream contract = AuditEventJsonTest.class.getResourceAsStream("/contract/openapi.yaml")) {
            openApi = new ObjectMapper(new YAMLFactory()).readTree(contract);
        }
    }

    @Test
    void completeEvent_conformsToAuditEventInput() throws Exception {
        AuditFact fact = AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:op-1")
                .detail("holder", Map.of("identifier", "12345678Z", "name", "María García López"))
                .detail("issued_attributes", List.of("given_name", "family_name"))
                .detail("valid_from", Instant.parse("2026-10-01T09:12:44Z"))
                .detail("accepted_by_holder", true)
                .build();
        AuditActor actor = new AuditActor(ActorKind.PERSON, "12345678Z",
                "María García López", "maria@example.org", "VATES-B00000000", "urn:uuid:att-1");
        AuditEvent event = AuditEvent.of(AuditDraft.success(fact),
                new AuditContext("red-logistica", "VATES-B00000000", "op-1", actor),
                UUID.fromString("5d1c7a40-2f0e-4b8a-9a61-0c3e5f7d2b11"), Instant.parse("2026-10-01T09:12:44.123Z"),
                "2.2.0", "issuer");

        JsonNode json = JSON.readTree(writer.write(event));

        assertThat(validate(json)).isEmpty();
        assertThat(json.get("event_id").asText()).isEqualTo("5d1c7a40-2f0e-4b8a-9a61-0c3e5f7d2b11");
        assertThat(json.get("occurred_at").asText()).isEqualTo("2026-10-01T09:12:44.123Z");
        assertThat(json.get("result").asText()).isEqualTo("Success");
        assertThat(json.at("/actor/kind").asText()).isEqualTo("Person");
        assertThat(json.at("/actor/attestation_id").asText()).isEqualTo("urn:uuid:att-1");
        assertThat(json.at("/details/valid_from").asText()).isEqualTo("2026-10-01T09:12:44Z");
    }

    @Test
    void minimalDeniedEvent_conformsAndOmitsAbsentFields() throws Exception {
        AuditFact fact = AuditFact.builder("AuthorizationDecision").action("Decide").resource("issuance:op-2")
                .detail("requested_action", null).build();
        AuditEvent event = AuditEvent.of(AuditDraft.denied(fact, "PowerMissing"),
                new AuditContext("sandbox", null, "op-2", AuditActor.system("issuer")),
                UUID.randomUUID(), Instant.parse("2026-10-01T09:12:44Z"), "2.2.0", "issuer");

        JsonNode json = JSON.readTree(writer.write(event));

        assertThat(validate(json)).isEmpty();
        assertThat(json.has("organization")).isFalse();
        assertThat(json.has("recorded_at")).isFalse();
        assertThat(json.get("actor").size()).isEqualTo(2);
        assertThat(json.get("reason").asText()).isEqualTo("PowerMissing");
        assertThat(json.get("details").isObject()).isTrue();
        assertThat(json.get("details").isEmpty()).isTrue();
    }

    @Test
    void successEvent_hasNoReason() throws Exception {
        AuditFact fact = AuditFact.builder("IssuanceWithdrawn").action("Withdraw").resource("issuance:op-3").build();
        AuditEvent event = AuditEvent.of(AuditDraft.success(fact),
                new AuditContext("sandbox", null, "op-3", AuditActor.system("issuer")),
                UUID.randomUUID(), Instant.now(), "2.2.0", "issuer");

        assertThat(JSON.readTree(writer.write(event)).has("reason")).isFalse();
    }

    @Test
    void contractValidation_detectsAFieldTheContractDoesNotDeclare() throws Exception {
        AuditFact fact = AuditFact.builder("IssuanceWithdrawn").action("Withdraw").resource("issuance:op-4").build();
        AuditEvent event = AuditEvent.of(AuditDraft.success(fact),
                new AuditContext("sandbox", null, "op-4", AuditActor.system("issuer")),
                UUID.randomUUID(), Instant.now(), "2.2.0", "issuer");
        ObjectNode withRecordedAt = (ObjectNode) JSON.readTree(writer.write(event));
        withRecordedAt.put("recorded_at", "2026-10-01T09:12:45Z");

        assertThat(validate(withRecordedAt)).isNotEmpty();
    }

    private static Set<ValidationMessage> validate(JsonNode instance) {
        ObjectNode document = openApi.deepCopy();
        document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        document.put("$ref", "#/components/schemas/AuditEventInput");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document).validate(instance);
    }
}
