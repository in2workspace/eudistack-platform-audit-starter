package com.eudistack.auditoria.support;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.EventResult;
import com.eudistack.auditoria.domain.EventStatus;
import com.eudistack.auditoria.port.AuditHttpClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What both clients of {@code POST /api/v1/events} have to do (DT-002 §3.3, RQ-26, EC-06), against a stand-in of the
 * Audit API. Includes {@code AuditHttpClientHeadersTest} of DT-002 §2.3: only the explicit headers travel.
 */
public abstract class AuditHttpClientContract {

    private static final String PATH = "/api/v1/events";
    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @RegisterExtension
    static final WireMockExtension AUDIT_API = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    protected final FakeAccessTokens tokens = new FakeAccessTokens();

    protected abstract AuditHttpClient client(URI baseUrl, FakeAccessTokens tokens);

    private AuditHttpClient client() {
        return client(URI.create(AUDIT_API.baseUrl()), tokens);
    }

    @Test
    void sendsTheStoredPayloadsAsTheEventsArray_asTheyAre() {
        AUDIT_API.stubFor(post(PATH).willReturn(ok("{\"results\":[]}")));

        client().postBatch(List.of(event("{\"a\": 1}"), event("{\"b\": [true]}")), null);

        AUDIT_API.verify(postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(equalToJson("{\"events\":[{\"a\":1},{\"b\":[true]}]}")));
    }

    @Test
    void sendsOnlyTheExplicitHeaders() {
        AUDIT_API.stubFor(post(PATH).willReturn(ok("{\"results\":[]}")));

        client().postBatch(List.of(event("{}")), TRACEPARENT);

        AUDIT_API.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer token-1"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("traceparent", equalTo(TRACEPARENT))
                .withoutHeader("Origin")
                .withoutHeader("Cookie")
                .withoutHeader("X-Forwarded-For")
                .withoutHeader("X-Forwarded-Host")
                .withoutHeader("X-Tenant-Id")
                .withoutHeader("tracestate"));
    }

    @Test
    void withoutTraceparent_sendsNone() {
        AUDIT_API.stubFor(post(PATH).willReturn(ok("{\"results\":[]}")));

        client().postBatch(List.of(event("{}")), null);

        AUDIT_API.verify(postRequestedFor(urlEqualTo(PATH)).withoutHeader("traceparent"));
    }

    @Test
    void ok_readsTheResultOfEachEvent_andSkipsUnreadableEntries() {
        UUID accepted = UUID.randomUUID();
        UUID rejected = UUID.randomUUID();
        AUDIT_API.stubFor(post(PATH).willReturn(ok("""
                {"results": [
                  {"event_id": "%s", "status": "accepted"},
                  {"event_id": "%s", "status": "rejected",
                   "error": {"type": "invalid_event", "title": "The event does not match its type", "status": 422}},
                  {"event_id": "not-a-uuid", "status": "accepted"},
                  {"event_id": "%s", "status": "lost"}
                ]}""".formatted(accepted, rejected, UUID.randomUUID()))));

        AuditApiResponse response = client().postBatch(List.of(event("{}")), null);

        assertThat(response).isEqualTo(new AuditApiResponse.Recorded(List.of(
                new EventResult(accepted, EventStatus.ACCEPTED, null),
                new EventResult(rejected, EventStatus.REJECTED, "invalid_event"))));
    }

    @Test
    void okWithoutResults_isAnInvalidResponse() {
        AUDIT_API.stubFor(post(PATH).willReturn(ok("<html>proxy page</html>")));

        assertThat(client().postBatch(List.of(event("{}")), null))
                .isEqualTo(new AuditApiResponse.Failed(200, AuditApiResponse.Failed.INVALID_RESPONSE, null));
    }

    @Test
    void unavailable_readsTheProblemTypeAndRetryAfter() {
        AUDIT_API.stubFor(post(PATH).willReturn(problem(503, "service_unavailable").withHeader("Retry-After", "10")));

        assertThat(client().postBatch(List.of(event("{}")), null))
                .isEqualTo(new AuditApiResponse.Failed(503, "service_unavailable", Duration.ofSeconds(10)));
    }

    @Test
    void badRequest_andMethodNotAllowed_areFailures() {
        AUDIT_API.stubFor(post(PATH).willReturn(problem(400, "BAD_REQUEST")));
        assertThat(client().postBatch(List.of(event("{}")), null))
                .isEqualTo(new AuditApiResponse.Failed(400, "BAD_REQUEST", null));

        AUDIT_API.stubFor(post(PATH).willReturn(aResponse().withStatus(405)));
        assertThat(client().postBatch(List.of(event("{}")), null))
                .isEqualTo(new AuditApiResponse.Failed(405, null, null));
    }

    @Test
    void unauthorized_getsANewTokenOnce_andRetries() {
        AUDIT_API.stubFor(post(PATH).withHeader("Authorization", equalTo("Bearer token-1"))
                .willReturn(problem(401, "invalid_token")));
        AUDIT_API.stubFor(post(PATH).withHeader("Authorization", equalTo("Bearer token-2"))
                .willReturn(ok("{\"results\":[]}")));

        AuditApiResponse response = client().postBatch(List.of(event("{}")), null);

        assertThat(response).isEqualTo(new AuditApiResponse.Recorded(List.of()));
        assertThat(tokens.refreshes()).isEqualTo(1);
        AUDIT_API.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void unauthorizedAgain_givesUpAfterTheOneRefresh() {
        AUDIT_API.stubFor(post(PATH).willReturn(problem(401, "invalid_token")));

        assertThat(client().postBatch(List.of(event("{}")), null))
                .isEqualTo(new AuditApiResponse.Failed(401, "invalid_token", null));
        assertThat(tokens.refreshes()).isEqualTo(1);
        AUDIT_API.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void slowApi_isUnreachableOnceTheReadTimeoutPasses() {
        AUDIT_API.stubFor(post(PATH).willReturn(ok("{\"results\":[]}").withFixedDelay(4_000)));
        long start = System.nanoTime();

        AuditApiResponse response = client().postBatch(List.of(event("{}")), null);

        assertThat(response).isInstanceOf(AuditApiResponse.Unreachable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3_500));
    }

    @Test
    void nobodyListening_isUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThat(client(URI.create("http://localhost:" + closedPort), tokens).postBatch(List.of(event("{}")), null))
                .isInstanceOf(AuditApiResponse.Unreachable.class);
    }

    @Test
    void withoutAToken_nothingIsSent() {
        tokens.fail();

        AuditApiResponse response = client().postBatch(List.of(event("{}")), null);

        assertThat(response).isInstanceOf(AuditApiResponse.Unreachable.class);
        assertThat(((AuditApiResponse.Unreachable) response).cause()).contains("access token");
        AUDIT_API.verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void batchOutsideTheContract_isRejected() {
        assertThatThrownBy(() -> client().postBatch(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client().postBatch(Collections.nCopies(101, event("{}")), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    protected static ClaimedEvent event(String payload) {
        return new ClaimedEvent(UUID.randomUUID(), "sandbox", "IssuanceCompleted", payload, null, 1);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder ok(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withHeader("Cache-Control", "no-store").withBody(body);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder problem(int status, String type) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/problem+json")
                .withBody("{\"type\":\"" + type + "\",\"title\":\"" + type + "\",\"status\":" + status + "}");
    }
}
