package com.eudistack.auditoria.port;

import org.springframework.lang.Nullable;

/**
 * M2M access token of the producer for the Audit API, servlet variant (docs/decisions.md, D-S9). Each producer
 * implements it with the system credentials it uses against the Verifier; the token needs the power
 * {@code organization · EUDISTACK · Audit · Write}. It is never configured in {@code application.yml}.
 *
 * <p>Only the dispatcher calls it, never the business path.
 */
public interface AuditAccessTokenProvider {

    /**
     * A valid access token, possibly cached.
     *
     * @param audience {@code auditoria.client.audience}; null while the Audit API does not enforce one (D-S10)
     */
    String accessToken(@Nullable String audience);

    /** A newly obtained access token, bypassing any cache. Called once after a 401 (EC-06). */
    String refreshAccessToken(@Nullable String audience);
}
