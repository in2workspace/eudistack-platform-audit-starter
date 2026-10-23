package com.eudistack.auditoria.application;

import com.eudistack.auditoria.context.ReactorAuditContextAccessor;
import com.eudistack.auditoria.context.ReactorAuditContexts;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditOutcome;
import com.eudistack.auditoria.port.AuditOutcomeClassifier;
import com.eudistack.auditoria.r2dbc.R2dbcOutboxStore;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 AD-6 with the D-S32 rules, on a reactive producer: the operator and {@code @AuditUseCase} on a {@code Mono}
 * method stage their facts as {@code Success} inside the business {@code TransactionalOperator}; a failure rolls back
 * and its classified event with it; outside a transaction, nothing of the business runs (EC-09).
 */
class ReactiveAuditUseCaseIT extends OutboxIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ConnectionFactory connectionFactory = PostgresTestDatabase.connectionFactory();
    private final DatabaseClient client = DatabaseClient.create(connectionFactory);
    private final TransactionalOperator businessTransaction =
            TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    private final AuditOutcomeClassifier classifier = error -> error instanceof PowerMissingException
            ? Optional.of(AuditOutcome.denied("PowerMissing"))
            : Optional.empty();
    private final ReactiveUseCaseAuditor useCases = new ReactiveUseCaseAuditor(
            new ReactiveOutboxAuditPublisher(new AuditStaging("2.2.0", "issuer", Clock.systemUTC()),
                    new R2dbcOutboxStore(connectionFactory), new ReactorAuditContextAccessor(), () -> { }),
            classifier);

    private final AuditContext request = new AuditContext("sandbox", null, "op-1", AuditActor.system("issuer"));

    @Test
    void operatorInsideTheTransaction_stagesItsFactAsSuccess() {
        businessTransaction.transactional(useCases.run(List.of(issuance()), insertBusiness()))
                .contextWrite(ReactorAuditContexts.with(request)).block(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.audit_outbox WHERE event_type = 'IssuanceCompleted'"))
                .isEqualTo(1);
    }

    @Test
    void factPreparedInsideTheMono_isStaged() {
        Mono<Void> issue = insertBusiness().then(ReactiveAuditUseCases.prepare(issuance()));

        businessTransaction.transactional(useCases.run(List.of(), issue))
                .contextWrite(ReactorAuditContexts.with(request)).block(TIMEOUT);

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
    }

    @Test
    void failure_rollsBackWithItsClassifiedEvent() {
        Mono<Void> denied = insertBusiness().then(Mono.error(new PowerMissingException()));

        StepVerifier.create(businessTransaction.transactional(useCases.run(List.of(issuance()), denied))
                        .contextWrite(ReactorAuditContexts.with(request)))
                .expectError(PowerMissingException.class).verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void outsideATransaction_nothingOfTheBusinessRuns() {
        StepVerifier.create(useCases.run(List.of(issuance()), insertBusiness())
                        .contextWrite(ReactorAuditContexts.with(request)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("EC-09"))
                .verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
    }

    @Test
    void annotatedMonoMethod_isAuditedThroughTheProxy() {
        ProxyFactory factory = new ProxyFactory(new IssuanceService(client));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new DefaultPointcutAdvisor(AnnotationMatchingPointcut.forMethodAnnotation(AuditUseCase.class),
                new AuditUseCaseInterceptor(() -> null, () -> useCases)));
        IssuanceService service = (IssuanceService) factory.getProxy();

        businessTransaction.transactional(service.issue(UUID.randomUUID()))
                .contextWrite(ReactorAuditContexts.with(request)).block(TIMEOUT);

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
    }

    @Test
    void publisherOff_runsTheActionOnly() {
        ReactiveUseCaseAuditor off = new ReactiveUseCaseAuditor(new DisabledReactiveAuditPublisher(), classifier);

        off.run(List.of(issuance()), insertBusiness().then(ReactiveAuditUseCases.prepare(issuance()))).block(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void prepareOutsideAUseCase_isAnErrorSignal() {
        StepVerifier.create(ReactiveAuditUseCases.prepare(issuance()))
                .expectError(IllegalStateException.class).verify(TIMEOUT);
    }

    private Mono<Void> insertBusiness() {
        return insert(client, UUID.randomUUID());
    }

    private static Mono<Void> insert(DatabaseClient client, UUID id) {
        return client.sql("INSERT INTO sandbox.issuance (id) VALUES (:id)").bind("id", id).fetch().rowsUpdated().then();
    }

    static AuditFact issuance() {
        return AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:op-1").build();
    }

    static class IssuanceService {

        private final DatabaseClient client;

        IssuanceService(DatabaseClient client) {
            this.client = client;
        }

        @AuditUseCase
        public Mono<Void> issue(UUID id) {
            return insert(client, id).then(ReactiveAuditUseCases.prepare(issuance()));
        }
    }

    static class PowerMissingException extends RuntimeException {
    }
}
