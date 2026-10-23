package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * {@code POST /api/v1/events} of the Audit API, reactive variant: same request and outcomes as
 * {@link AuditHttpClient}. A transport or HTTP failure is a value, never an error signal; only invalid arguments
 * are.
 */
public interface ReactiveAuditHttpClient {

    /** @see AuditHttpClient#postBatch */
    Mono<AuditApiResponse> postBatch(List<ClaimedEvent> events, @Nullable String traceparent);
}
