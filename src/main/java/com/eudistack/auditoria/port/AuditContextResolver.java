package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.AuditContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;

import java.util.Optional;

/**
 * Translates the authenticated request of a servlet producer into its {@link AuditContext}
 * (docs/decisions.md, D-S6). Each producer provides one bean: only it knows where its tenant filter leaves the
 * tenant and which claims carry the actor and the organization.
 *
 * <p>The starter calls it lazily, at the first {@code stage} of the request, so the filters of the producer have
 * already run, and reuses the result for the rest of the request.
 */
@FunctionalInterface
public interface AuditContextResolver {

    /**
     * @param authentication the current authentication, null on an unauthenticated endpoint
     * @return the context, or empty when the request has none; a {@code stage} in that request then fails.
     *         A null {@code correlationId} lets the starter generate one for the request.
     */
    Optional<AuditContext> resolve(HttpServletRequest request, @Nullable Authentication authentication);
}
