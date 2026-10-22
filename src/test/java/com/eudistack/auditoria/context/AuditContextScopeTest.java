package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §2.3 {@code AuditContextScopeTest} (RQ-22, EC-18, AD-10), servlet side: a background process opens its
 * context explicitly, with the tenant of the record it processes and the producer as actor; scopes nest, restore
 * the previous context and never leak out of their thread.
 */
class AuditContextScopeTest {

    private final ThreadLocalAuditContextAccessor accessor = new ThreadLocalAuditContextAccessor();

    @AfterEach
    void noContextLeaks() {
        assertThat(accessor.current()).isEmpty();
    }

    @Test
    void system_opensTheTenantWithTheProducerAsActor_andANewCorrelation() {
        try (AuditContextScope scope = AuditContexts.system("red-logistica", "issuer")) {
            AuditContext context = accessor.current().orElseThrow();

            assertThat(context.tenant()).isEqualTo("red-logistica");
            assertThat(context.organization()).isNull();
            assertThat(context.actor()).isEqualTo(AuditActor.system("issuer"));
            assertThat(context.correlationId()).isNotBlank();
            assertThat(accessor.current().orElseThrow().correlationId()).isEqualTo(context.correlationId());
        }
    }

    @Test
    void system_withTheCorrelationOfTheOperation_keepsIt() {
        try (AuditContextScope scope = AuditContexts.system("sandbox", "issuer", "issuance-42")) {
            assertThat(accessor.current().orElseThrow().correlationId()).isEqualTo("issuance-42");
        }
    }

    @Test
    void nestedScopes_restoreThePreviousContextWhenClosed() {
        try (AuditContextScope outer = AuditContexts.system("sandbox", "issuer", "op-1")) {
            try (AuditContextScope inner = AuditContexts.system("kpmg", "issuer", "op-2")) {
                assertThat(accessor.current()).map(AuditContext::tenant).contains("kpmg");
            }
            assertThat(accessor.current()).map(AuditContext::tenant).contains("sandbox");
        }
    }

    @Test
    void withCorrelationId_changesOnlyTheCorrelation_untilClosed() {
        try (AuditContextScope process = AuditContexts.system("sandbox", "issuer", "op-1")) {
            try (AuditContextScope issuance = AuditContexts.withCorrelationId("issuance-42")) {
                assertThat(accessor.current().orElseThrow())
                        .isEqualTo(new AuditContext("sandbox", null, "issuance-42", AuditActor.system("issuer")));
            }
            assertThat(accessor.current()).map(AuditContext::correlationId).contains("op-1");
        }
    }

    @Test
    void withCorrelationId_withoutAContext_leavesNone() {
        try (AuditContextScope scope = AuditContexts.withCorrelationId("issuance-42")) {
            assertThat(accessor.current()).isEmpty();
        }
    }

    @Test
    void open_givesTheContextAsIs_withACorrelationWhenItHasNone() {
        AuditContext person = new AuditContext("sandbox", "VATES-B00000002", null,
                AuditActor.person("12345678Z", "María García", "maria@example.org"));

        try (AuditContextScope scope = AuditContexts.open(person)) {
            AuditContext current = accessor.current().orElseThrow();
            assertThat(current.actor()).isEqualTo(person.actor());
            assertThat(current.correlationId()).isNotBlank();
        }
    }

    @Test
    void closingOutOfOrder_isRejected() {
        AuditContextScope outer = AuditContexts.system("sandbox", "issuer");
        AuditContextScope inner = AuditContexts.system("kpmg", "issuer");

        assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reverse order");

        inner.close();
        outer.close();
    }

    @Test
    void closingTwice_doesNothing() {
        AuditContextScope scope = AuditContexts.system("sandbox", "issuer");
        scope.close();
        scope.close();
    }

    @Test
    void aScope_isOnlyVisibleInItsThread() throws Exception {
        try (AuditContextScope scope = AuditContexts.system("sandbox", "issuer")) {
            Optional<AuditContext> elsewhere = CompletableFuture.supplyAsync(accessor::current).get();

            assertThat(elsewhere).isEmpty();
            assertThat(accessor.current()).isPresent();
        }
    }

    @Test
    void invalidArguments_areRejected() {
        assertThatThrownBy(() -> AuditContexts.system(" ", "issuer")).hasMessageContaining("tenant");
        assertThatThrownBy(() -> AuditContexts.withCorrelationId(" ")).hasMessageContaining("correlation_id");
    }
}
