package com.eudistack.auditoria.http;

import com.eudistack.auditoria.application.AuditEventJson;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditEvent;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.EventResult;
import com.eudistack.auditoria.domain.EventStatus;
import com.eudistack.auditoria.support.FakeAccessTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §2.3 {@code AuditApiContractIT}: what the client sends is an {@code EventBatch} of the Audit API contract
 * ({@code src/test/resources/contract/openapi.yaml}, a copy of the API's {@code docs/openapi.yaml}), and the
 * contract's own example answers are read as the dispatcher needs them.
 */
class AuditApiContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static JsonNode openApi;

    @RegisterExtension
    static final WireMockExtension AUDIT_API = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final RestClientAuditHttpClient client = new RestClientAuditHttpClient(URI.create(AUDIT_API.baseUrl()),
            "/api/v1/events", null, new FakeAccessTokens().blocking());

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream contract = AuditApiContractIT.class.getResourceAsStream("/contract/openapi.yaml")) {
            openApi = new ObjectMapper(new YAMLFactory()).readTree(contract);
        }
    }

    @Test
    void request_isAnEventBatchOfTheContract() throws Exception {
        AUDIT_API.stubFor(post("/api/v1/events").willReturn(json(200, "{\"results\":[]}")));

        client.postBatch(List.of(staged("IssuanceCompleted"), staged("Revocation")), null);

        LoggedRequest request = AUDIT_API.findAll(postRequestedFor(urlEqualTo("/api/v1/events"))).getFirst();
        assertThat(validate("EventBatch", JSON.readTree(request.getBodyAsString()))).isEmpty();
    }

    @Test
    void theContractsMixedExample_isReadAsTheDispatcherNeedsIt() {
        JsonNode mixed = openApi.at("/paths/~1api~1v1~1events/post/responses/200/content/application~1json"
                + "/examples/mixed/value");
        assertThat(validate("EventBatchResult", mixed)).isEmpty();
        AUDIT_API.stubFor(post("/api/v1/events").willReturn(json(200, mixed.toString())));

        AuditApiResponse response = client.postBatch(List.of(staged("IssuanceCompleted")), null);

        assertThat(response).isEqualTo(new AuditApiResponse.Recorded(List.of(
                new EventResult(UUID.fromString("5d1c7a40-2f0e-4b8a-9a61-0c3e5f7d2b11"), EventStatus.ACCEPTED, null),
                new EventResult(UUID.fromString("8a2e1f33-7c4d-4e0b-b1a2-3d4c5e6f7a80"), EventStatus.DUPLICATE, null),
                new EventResult(UUID.fromString("c0ffee00-1111-4222-8333-444455556666"), EventStatus.REJECTED,
                        "invalid_event"))));
    }

    @Test
    void aProblemOfTheContract_isReadWithItsType() throws Exception {
        String problem = "{\"type\":\"service_unavailable\",\"title\":\"Audit store unavailable\",\"status\":503}";
        assertThat(validate("Problem", JSON.readTree(problem))).isEmpty();
        AUDIT_API.stubFor(post("/api/v1/events").willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/problem+json").withHeader("Retry-After", "5").withBody(problem)));

        assertThat(client.postBatch(List.of(staged("IssuanceCompleted")), null))
                .isInstanceOfSatisfying(AuditApiResponse.Failed.class,
                        failed -> assertThat(failed.problemType()).isEqualTo("service_unavailable"));
    }

    /** A row as {@code stage} leaves it: the real {@code AuditEventInput} JSON. */
    private static ClaimedEvent staged(String eventType) {
        AuditFact fact = AuditFact.builder(eventType).action("Issue").resource("issuance:op-1")
                .details(Map.of("holder", Map.of("identifier", "12345678Z"))).build();
        AuditEvent event = AuditEvent.of(AuditDraft.success(fact),
                new AuditContext("sandbox", "VATES-B00000002", "op-1", AuditActor.system("issuer")),
                UUID.randomUUID(), Instant.now(), "2.2.0", "issuer");
        return new ClaimedEvent(event.eventId(), event.tenant(), eventType, new AuditEventJson().write(event), null, 1);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static Set<ValidationMessage> validate(String component, JsonNode instance) {
        ObjectNode document = openApi.deepCopy();
        document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        document.put("$ref", "#/components/schemas/" + component);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document).validate(instance);
    }
}
