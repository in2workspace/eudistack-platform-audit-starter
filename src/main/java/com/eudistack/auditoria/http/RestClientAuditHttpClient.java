package com.eudistack.auditoria.http;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.eudistack.auditoria.port.AuditHttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@link AuditHttpClient} on {@link RestClient}, for servlet producers (DT-002 §3.4.1). A client of its own, not the
 * producer's builder: customizers of the producer could add headers or filters, and the request carries only the
 * explicit ones (RQ-26). On a 401 it gets a new token once and retries (EC-06).
 */
public final class RestClientAuditHttpClient implements AuditHttpClient {

    private final RestClient client;
    private final URI endpoint;
    private final AuditAccessTokenProvider tokens;
    private final String audience;

    /**
     * @param baseUrl  origin of the internal write host, without path
     * @param path     path of {@code recordEvents}
     * @param audience audience of the M2M token, null while the API does not enforce one
     */
    public RestClientAuditHttpClient(URI baseUrl, String path, @Nullable String audience,
                                     AuditAccessTokenProvider tokens) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(AuditApiExchange.jdkClient());
        requestFactory.setReadTimeout(AuditApiTimeouts.READ);
        this.client = RestClient.builder().requestFactory(requestFactory).build();
        this.endpoint = baseUrl.resolve(path);
        this.audience = audience;
        this.tokens = tokens;
    }

    @Override
    public AuditApiResponse postBatch(List<ClaimedEvent> events, @Nullable String traceparent) {
        String body = AuditApiExchange.body(events);
        String token;
        try {
            token = tokens.accessToken(audience);
        } catch (RuntimeException e) {
            return AuditApiExchange.unreachable("access token", e);
        }
        AuditApiResponse response = post(body, token, traceparent);
        if (response instanceof AuditApiResponse.Failed failed && failed.status() == 401) {
            try {
                token = tokens.refreshAccessToken(audience);
            } catch (RuntimeException e) {
                return response;
            }
            response = post(body, token, traceparent);
        }
        return response;
    }

    private AuditApiResponse post(String body, String token, String traceparent) {
        try {
            return client.post()
                    .uri(endpoint)
                    .headers(headers -> {
                        headers.setBearerAuth(token);
                        headers.setContentType(MediaType.APPLICATION_JSON);
                        if (traceparent != null) {
                            headers.set(AuditApiExchange.TRACEPARENT, traceparent);
                        }
                    })
                    .body(body)
                    .exchange((request, response) -> AuditApiExchange.response(
                            response.getStatusCode().value(),
                            StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8),
                            response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
        } catch (RestClientException e) {
            return AuditApiExchange.unreachable("POST", e.getMostSpecificCause());
        }
    }
}
