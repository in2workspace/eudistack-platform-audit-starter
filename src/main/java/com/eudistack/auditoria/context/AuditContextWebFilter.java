package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.ReactiveAuditContextResolver;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Puts the audit context of each request of a reactive producer in the Reactor Context (docs/decisions.md, D-S6).
 * The context is not resolved here: the producer's {@link ReactiveAuditContextResolver} runs the first time a
 * {@code stage} asks for it, with the authentication Spring Security left in the Reactor Context and the
 * attributes the producer's tenant filter set, so the order of the filters does not matter. The result is cached
 * for the rest of the request.
 */
public final class AuditContextWebFilter implements WebFilter, Ordered {

    private final ReactiveAuditContextResolver resolver;

    public AuditContextWebFilter(ReactiveAuditContextResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Mono<AuditContext> resolution = ReactiveSecurityContextHolder.getContext()
                .mapNotNull(SecurityContext::getAuthentication)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> resolver.resolve(exchange, authentication.orElse(null)))
                .map(CorrelationIds::ensure)
                .cache();
        return chain.filter(exchange).contextWrite(ReactorAuditContexts.lazy(resolution));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
