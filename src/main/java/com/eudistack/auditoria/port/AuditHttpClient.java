package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditApiResponse;
import com.eudistack.auditoria.domain.ClaimedEvent;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * {@code POST /api/v1/events} of the Audit API, servlet variant (DT-002 §3.3, RQ-26). The request carries only
 * explicit headers: the M2M bearer of this service, {@code Content-Type} and {@code traceparent}; nothing of any
 * incoming request. It never throws on a transport or HTTP failure: see {@link AuditApiResponse}.
 */
public interface AuditHttpClient {

    /**
     * @param events      1 to {@link com.eudistack.auditoria.domain.OutboxLimits#MAX_BATCH_SIZE} claimed rows,
     *                    sent with their stored payload as is
     * @param traceparent the W3C {@code traceparent} to send, null for none
     */
    AuditApiResponse postBatch(List<ClaimedEvent> events, @Nullable String traceparent);
}
