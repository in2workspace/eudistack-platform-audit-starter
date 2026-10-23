package com.eudistack.auditoria.application;

import com.eudistack.auditoria.context.ReactorAuditContextAccessor;
import com.eudistack.auditoria.context.ReactorAuditContexts;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditTenantMismatchException;
import com.eudistack.auditoria.r2dbc.R2dbcOutboxStore;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §3.3 and §3.4.2 on a reactive producer (R2DBC): {@code stage} composes in the business
 * {@code TransactionalOperator} and nowhere else (RQ-01, EC-09), every failure is an error signal that rolls the
 * business back, and the dispatcher is woken once, after the commit. The D-S8 pattern works without blocking.
 */
class ReactiveAuditPublisherIT extends OutboxIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T08:00:00Z"), ZoneOffset.UTC);

    private final ConnectionFactory connectionFactory = PostgresTestDatabase.connectionFactory();
    private final DatabaseClient client = DatabaseClient.create(connectionFactory);
    private final TransactionalOperator businessTransaction =
            TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));
    private final AtomicInteger wakeUps = new AtomicInteger();
    private final ReactiveOutboxAuditPublisher publisher = new ReactiveOutboxAuditPublisher(
            new AuditStaging("2.2.0", "issuer", CLOCK), new R2dbcOutboxStore(connectionFactory),
            new ReactorAuditContextAccessor(), wakeUps::incrementAndGet);

    private final AuditContext context = new AuditContext("sandbox", "VATES-B00000000", "op-1",
            AuditActor.system("issuer"));

    @Test
    void stage_inTheBusinessTransaction_commitsWithIt_andWakesTheDispatcherAfterTheCommit() {
        Mono<Void> issuance = insertBusiness(UUID.randomUUID())
                .then(publisher.stage(AuditDraft.success(issuanceCompleted().build())))
                .then(publisher.stage(AuditDraft.success(issuanceCompleted().build())))
                .then(Mono.fromRunnable(() -> assertThat(wakeUps).as("not before the commit").hasValue(0)));

        businessTransaction.transactional(issuance).contextWrite(ReactorAuditContexts.with(context)).block(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT event_id) FROM public.audit_outbox WHERE tenant = 'sandbox' "
                + "AND correlation_id = 'op-1'")).isEqualTo(2);
        assertThat(wakeUps).hasValue(1);
    }

    @Test
    void rollbackOfTheBusiness_discardsTheEvent_andWakesNobody() {
        Mono<Void> issuance = insertBusiness(UUID.randomUUID())
                .then(publisher.stage(AuditDraft.success(issuanceCompleted().build())))
                .then(Mono.error(new IllegalStateException("business failure")));

        StepVerifier.create(businessTransaction.transactional(issuance).contextWrite(ReactorAuditContexts.with(context)))
                .expectErrorMessage("business failure").verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        assertThat(wakeUps).hasValue(0);
    }

    @Test
    void withoutATransaction_stageIsAnErrorSignal_andInsertsNothing() {
        StepVerifier.create(publisher.stage(AuditDraft.success(issuanceCompleted().build()))
                        .contextWrite(ReactorAuditContexts.with(context)))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("EC-09"))
                .verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void withoutAContext_stageIsAnErrorSignal_andTheBusinessRollsBack() {
        Mono<Void> issuance = insertBusiness(UUID.randomUUID())
                .then(publisher.stage(AuditDraft.success(issuanceCompleted().build())));

        StepVerifier.create(businessTransaction.transactional(issuance))
                .expectErrorSatisfies(error -> assertThat(error).hasMessageContaining("audit context"))
                .verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
    }

    @Test
    void draftOfAnotherTenant_isAnErrorSignal_andTheBusinessRollsBack() {
        Mono<Void> issuance = insertBusiness(UUID.randomUUID())
                .then(publisher.stage(AuditDraft.success(issuanceCompleted().tenant("kpmg").build())));

        StepVerifier.create(businessTransaction.transactional(issuance).contextWrite(ReactorAuditContexts.with(context)))
                .expectError(AuditTenantMismatchException.class).verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void deniedAfterTheBusinessRolledBack_survivesInAShortTransactionOfTheProducer() {
        Mono<Void> revocation = businessTransaction.transactional(insertBusiness(UUID.randomUUID())
                        .then(Mono.<Void>error(new IllegalStateException("PowerMissing"))))
                .onErrorResume(IllegalStateException.class, denied -> businessTransaction.transactional(
                                publisher.stage(AuditDraft.denied(issuanceCompleted().build(), denied.getMessage())))
                        .then(Mono.error(denied)));

        StepVerifier.create(revocation.contextWrite(ReactorAuditContexts.with(context)))
                .expectErrorMessage("PowerMissing").verify(TIMEOUT);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
        assertThat(wakeUps).hasValue(1);
    }

    private static AuditFact.Builder issuanceCompleted() {
        return AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:op-1");
    }

    private Mono<Void> insertBusiness(UUID id) {
        return client.sql("SELECT set_config('search_path', 'sandbox', true)").fetch().first()
                .then(client.sql("INSERT INTO issuance (id) VALUES (:id)").bind("id", id).fetch().rowsUpdated())
                .then();
    }
}
