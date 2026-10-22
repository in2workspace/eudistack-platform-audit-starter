package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.port.AuditContextResolver;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §2.3 {@code ThreadLocalAuditContextAccessorTest} (RQ-15), with the request filter of D-S6: the producer's
 * resolver runs once, only when the context is read, with the authentication and attributes the other filters
 * left, whatever their order; the thread is clean when the request ends.
 */
class ThreadLocalAuditContextAccessorTest {

    private final ThreadLocalAuditContextAccessor accessor = new ThreadLocalAuditContextAccessor();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final List<AuditContext> seen = new ArrayList<>();

    private final AuditContextResolver resolver = (request, authentication) -> {
        resolutions.incrementAndGet();
        String tenant = (String) request.getAttribute("tenantDomain");
        return tenant == null ? Optional.empty() : Optional.of(new AuditContext(tenant, "VATES-B00000002", null,
                AuditActor.system(authentication == null ? "anonymous" : authentication.getName())));
    };

    /** The producer's tenant filter, after the audit filter. */
    private final Filter tenantFilter = (request, response, chain) -> {
        request.setAttribute("tenantDomain", "sandbox");
        chain.doFilter(request, response);
    };

    /** Spring Security, also after the audit filter. */
    private final Filter securityFilter = (request, response, chain) -> {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("verifier-client", null));
        chain.doFilter(request, response);
    };

    @AfterEach
    void threadIsClean() {
        SecurityContextHolder.clearContext();
        assertThat(accessor.current()).isEmpty();
    }

    @Test
    void resolver_runsOnlyWhenTheContextIsRead() throws Exception {
        request(() -> { }, tenantFilter);

        assertThat(resolutions).hasValue(0);
    }

    @Test
    void resolver_seesTheAttributesAndAuthenticationOfFiltersThatRunAfterIt() throws Exception {
        request(this::read, tenantFilter, securityFilter);

        assertThat(seen).singleElement().satisfies(context -> {
            assertThat(context.tenant()).isEqualTo("sandbox");
            assertThat(context.organization()).isEqualTo("VATES-B00000002");
            assertThat(context.actor().id()).isEqualTo("verifier-client");
        });
    }

    @Test
    void resolver_runsOnce_andTheGeneratedCorrelationIsTheSameForTheWholeRequest() throws Exception {
        request(() -> {
            read();
            read();
        }, tenantFilter);

        assertThat(resolutions).hasValue(1);
        assertThat(seen).hasSize(2).extracting(AuditContext::correlationId).doesNotContainNull().containsOnly(
                seen.getFirst().correlationId());
    }

    @Test
    void withCorrelationId_inARequest_changesTheCorrelationUntilClosed() throws Exception {
        request(() -> {
            try (AuditContextScope issuance = AuditContexts.withCorrelationId("issuance-42")) {
                read();
            }
            read();
        }, tenantFilter);

        assertThat(seen.get(0).correlationId()).isEqualTo("issuance-42");
        assertThat(seen.get(1).correlationId()).isNotEqualTo("issuance-42");
        assertThat(seen.get(1).tenant()).isEqualTo("sandbox");
    }

    @Test
    void resolverWithoutContext_leavesNone() throws Exception {
        request(this::read);

        assertThat(seen).isEmpty();
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void failingRequest_stillLeavesTheThreadClean() {
        assertThatThrownBy(() -> request(() -> {
            read();
            throw new IllegalStateException("handler failure");
        }, tenantFilter)).hasRootCauseMessage("handler failure");
    }

    private void read() {
        accessor.current().ifPresent(seen::add);
    }

    private void request(Runnable handler, Filter... laterFilters) throws Exception {
        Filter[] filters = new Filter[laterFilters.length + 1];
        filters[0] = new AuditContextFilter(resolver);
        System.arraycopy(laterFilters, 0, filters, 1, laterFilters.length);
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException {
                try {
                    handler.run();
                } catch (IllegalStateException e) {
                    throw new ServletException(e);
                }
            }
        };
        new MockFilterChain(servlet, filters).doFilter(new MockHttpServletRequest("POST", "/revocations"),
                new MockHttpServletResponse());
    }
}
