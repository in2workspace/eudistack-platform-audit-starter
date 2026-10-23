package com.eudistack.auditoria.http;

import com.eudistack.auditoria.port.AuditHttpClient;
import com.eudistack.auditoria.support.AuditHttpClientContract;
import com.eudistack.auditoria.support.FakeAccessTokens;

import java.net.URI;

/**
 * The client contract on {@link RestClientAuditHttpClient}, the client of servlet producers.
 */
class RestClientAuditHttpClientTest extends AuditHttpClientContract {

    @Override
    protected AuditHttpClient client(URI baseUrl, FakeAccessTokens tokens) {
        return new RestClientAuditHttpClient(baseUrl, "/api/v1/events", null, tokens.blocking());
    }
}
