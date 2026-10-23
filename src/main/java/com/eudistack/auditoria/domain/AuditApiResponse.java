package com.eudistack.auditoria.domain;

import java.time.Duration;
import java.util.List;

/**
 * What came back from {@code POST /api/v1/events}, reduced to what the dispatcher decides on (DT-002 §3.4.3). The
 * client never throws: every outcome is one of these.
 */
public sealed interface AuditApiResponse {

    /** {@code 200}: the result of each event the API listed. An event it did not list stays in the outbox (EC-05). */
    record Recorded(List<EventResult> results) implements AuditApiResponse {

        public Recorded {
            results = List.copyOf(results);
        }
    }

    /**
     * Any other HTTP status, or a {@code 200} whose body is not an {@code EventBatchResult}
     * ({@link #INVALID_RESPONSE}).
     *
     * @param problemType the {@code type} of the {@code problem+json}, null when there is none
     * @param retryAfter  {@code Retry-After}, null when absent or not a number of seconds
     */
    record Failed(int status, String problemType, Duration retryAfter) implements AuditApiResponse {

        public static final String INVALID_RESPONSE = "invalid_response";
    }

    /** No HTTP answer: connection refused or reset, DNS, timeout, or no access token to send. */
    record Unreachable(String cause) implements AuditApiResponse {
    }
}
