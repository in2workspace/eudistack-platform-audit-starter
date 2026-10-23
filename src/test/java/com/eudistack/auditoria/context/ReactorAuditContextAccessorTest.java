package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.ReactiveAuditContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §2.3 {@code ReactorAuditContextAccessorTest} (RQ-15, RQ-22, AD-10), reactive side: explicit scopes in the
 * Reactor Context for background processes, and the request web filter of D-S6, which resolves once, only when the
 * context is read, with the authentication and attributes the other filters left.
 */
class ReactorAuditContextAccessorTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ReactorAuditContextAccessor accessor = new ReactorAuditContextAccessor();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final List<AuditContext> seen = new ArrayList<>();

    private final ReactiveAuditContextResolver resolver = (exchange, authentication) -> {
        resolutions.incrementAndGet();
        String tenant = exchange.getAttribute("tenantDomain");
        return tenant == null ? Mono.empty() : Mono.just(new AuditContext(tenant, null, null,
                AuditActor.system(authentication == null ? "anonymous" : authentication.getName())));
    };

    @Test
    void noContext_isEmpty() {
        StepVerifier.create(accessor.current()).expectComplete().verify(TIMEOUT);
    }

    @Test
    void system_writesTheTenantWithTheProducerAsActor_andANewCorrelation() {
        AuditContext context = accessor.current()
                .contextWrite(ReactorAuditContexts.system("red-logistica", "issuer"))
                .block(TIMEOUT);

        assertThat(context.tenant()).isEqualTo("red-logistica");
        assertThat(context.actor()).isEqualTo(AuditActor.system("issuer"));
        assertThat(context.correlationId()).isNotBlank();
    }

    @Test
    void withCorrelationId_changesOnlyTheCorrelationOfTheContextBelow() {
        AuditContext context = accessor.current()
                .contextWrite(ReactorAuditContexts.withCorrelationId("issuance-42"))
                .contextWrite(ReactorAuditContexts.system("sandbox", "issuer", "op-1"))
                .block(TIMEOUT);

        assertThat(context).isEqualTo(new AuditContext("sandbox", null, "issuance-42", AuditActor.system("issuer")));
    }

    @Test
    void withCorrelationId_withoutAContext_leavesNone() {
        StepVerifier.create(accessor.current().contextWrite(ReactorAuditContexts.withCorrelationId("issuance-42")))
                .expectComplete().verify(TIMEOUT);
    }

    @Test
    void webFilter_resolvesOnlyWhenTheContextIsRead() {
        request(exchange -> Mono.empty());

        assertThat(resolutions).hasValue(0);
    }

    @Test
    void webFilter_seesTheAttributesAndAuthenticationOfTheOtherFilters_andResolvesOnce() {
        request(exchange -> {
            exchange.getAttributes().put("tenantDomain", "sandbox");
            return read().then(read());
        });

        assertThat(resolutions).hasValue(1);
        assertThat(seen).hasSize(2).allSatisfy(context -> {
            assertThat(context.tenant()).isEqualTo("sandbox");
            assertThat(context.actor().id()).isEqualTo("issuer-client");
        });
        assertThat(seen.get(0).correlationId()).isNotBlank().isEqualTo(seen.get(1).correlationId());
    }

    @Test
    void webFilter_withCorrelationIdInTheRequest_changesTheCorrelation() {
        request(exchange -> {
            exchange.getAttributes().put("tenantDomain", "sandbox");
            return read().contextWrite(ReactorAuditContexts.withCorrelationId("issuance-42")).then(read());
        });

        assertThat(seen.get(0).correlationId()).isEqualTo("issuance-42");
        assertThat(seen.get(1).correlationId()).isNotEqualTo("issuance-42");
    }

    @Test
    void webFilter_resolverWithoutContext_leavesNone() {
        request(exchange -> read());

        assertThat(seen).isEmpty();
        assertThat(resolutions).hasValue(1);
    }

    private Mono<Void> read() {
        return accessor.current().doOnNext(seen::add).then();
    }

    /** One request through the audit web filter; Spring Security wrote its authentication around it. */
    private void request(WebFilterChain handler) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/issuances"));
        new AuditContextWebFilter(resolver).filter(exchange, handler)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new TestingAuthenticationToken("issuer-client", null)))
                .block(TIMEOUT);
    }
}
