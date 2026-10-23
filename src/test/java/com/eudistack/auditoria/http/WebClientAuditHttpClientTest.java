package com.eudistack.auditoria.http;

import com.eudistack.auditoria.port.AuditHttpClient;
import com.eudistack.auditoria.support.AuditHttpClientContract;
import com.eudistack.auditoria.support.FakeAccessTokens;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * The client contract on {@link WebClientAuditHttpClient}, the client of reactive producers, seen through a blocking
 * view in the tests only. Invalid arguments are error signals, not exceptions.
 */
class WebClientAuditHttpClientTest extends AuditHttpClientContract {

    @Override
    protected AuditHttpClient client(URI baseUrl, FakeAccessTokens tokens) {
        WebClientAuditHttpClient reactive = new WebClientAuditHttpClient(baseUrl, "/api/v1/events", null,
                tokens.reactive());
        return (events, traceparent) -> reactive.postBatch(events, traceparent).block(Duration.ofSeconds(10));
    }

    @Test
    void invalidBatch_isAnErrorSignal() {
        WebClientAuditHttpClient reactive = new WebClientAuditHttpClient(URI.create("http://localhost"),
                "/api/v1/events", null, tokens.reactive());

        StepVerifier.create(reactive.postBatch(List.of(), null))
                .expectError(IllegalArgumentException.class).verify(Duration.ofSeconds(5));
    }
}
