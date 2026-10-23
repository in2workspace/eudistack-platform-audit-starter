package com.eudistack.auditoria.context;

import com.eudistack.auditoria.port.AuditContextResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Opens the audit context of each request of a servlet producer (docs/decisions.md, D-S6). The context is not
 * resolved here: the producer's {@link AuditContextResolver} runs the first time a {@code stage} asks for it, when
 * Spring Security and the producer's tenant filter have already run, so the order of the filters does not matter.
 * The thread is clean again when the request ends.
 */
public final class AuditContextFilter extends OncePerRequestFilter {

    private final AuditContextResolver resolver;

    public AuditContextFilter(AuditContextResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (AuditContextScope scope = ThreadAuditContexts.open(() ->
                resolver.resolve(request, SecurityContextHolder.getContext().getAuthentication()))) {
            chain.doFilter(request, response);
        }
    }
}
