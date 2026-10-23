package com.eudistack.auditoria.http;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.port.ReactiveAuditAccessTokenProvider;
import com.eudistack.auditoria.port.ReactiveAuditHttpClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

/**
 * {@link ReactiveAuditHttpClient} on {@link WebClient}, for reactive producers (DT-002 §3.4.1). Same request and
 * rules as {@link RestClientAuditHttpClient}, on the JDK HTTP client, so it does not need Reactor Netty. Nothing
 * blocks.
 */
public final class WebClientAuditHttpClient implements ReactiveAuditHttpClient {

    private final WebClient client;
    private final URI endpoint;
    private final ReactiveAuditAccessTokenProvider tokens;
    private final String audience;

    /**
     * @param baseUrl  origin of the internal write host, without path
     * @param path     path of {@code recordEvents}
     * @param audience audience of the M2M token, null while the API does not enforce one
     */
    public WebClientAuditHttpClient(URI baseUrl, String path, @Nullable String audience,
                                    ReactiveAuditAccessTokenProvider tokens) {
        JdkClientHttpConnector connector = new JdkClientHttpConnector(AuditApiExchange.jdkClient());
        connector.setReadTimeout(AuditApiTimeouts.READ);
        this.client = WebClient.builder().clientConnector(connector).build();
        this.endpoint = baseUrl.resolve(path);
        this.audience = audience;
        this.tokens = tokens;
    }

    @Override
    public Mono<AuditApiResponse> postBatch(List<ClaimedEvent> events, @Nullable String traceparent) {
        return Mono.defer(() -> {
            String body = AuditApiExchange.body(events);
            return token(tokens.accessToken(audience))
                    .flatMap(token -> post(body, token, traceparent))
                    .flatMap(response -> response instanceof AuditApiResponse.Failed failed && failed.status() == 401
                            ? Mono.defer(() -> tokens.refreshAccessToken(audience))
                                    .flatMap(token -> post(body, token, traceparent))
                                    .onErrorResume(e -> Mono.just(response))
                                    .defaultIfEmpty(response)
                            : Mono.just(response))
                    .onErrorResume(TokenFailure.class, failure -> Mono.just(
                            AuditApiExchange.unreachable("access token", failure.getCause())));
        });
    }

    private Mono<AuditApiResponse> post(String body, String token, String traceparent) {
        return client.post()
                .uri(endpoint)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (traceparent != null) {
                        headers.set(AuditApiExchange.TRACEPARENT, traceparent);
                    }
                })
                .bodyValue(body)
                .exchangeToMono(response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(responseBody -> AuditApiExchange.response(response.statusCode().value(), responseBody,
                                response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER))))
                .onErrorResume(error -> Mono.just(AuditApiExchange.unreachable("POST", error)));
    }

    /** A token, or a {@link TokenFailure} when the provider fails or gives none. */
    private static Mono<String> token(Mono<String> token) {
        return Mono.defer(() -> token)
                .switchIfEmpty(Mono.error(() -> new IllegalStateException("the provider gave no token")))
                .onErrorMap(error -> !(error instanceof TokenFailure), TokenFailure::new);
    }

    private static final class TokenFailure extends RuntimeException {

        TokenFailure(Throwable cause) {
            super(cause);
        }
    }
}
