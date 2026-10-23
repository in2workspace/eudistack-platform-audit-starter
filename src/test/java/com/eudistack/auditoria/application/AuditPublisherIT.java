package com.eudistack.auditoria.application;

import com.eudistack.auditoria.context.AuditContextScope;
import com.eudistack.auditoria.context.AuditContexts;
import com.eudistack.auditoria.context.ThreadLocalAuditContextAccessor;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditDraftRejectedException;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditTenantMismatchException;
import com.eudistack.auditoria.jdbc.JdbcOutboxStore;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §3.3 on a servlet producer (JDBC): {@code stage} writes the event in the business transaction and nowhere
 * else (RQ-01, EC-09), fails the transaction on a foreign tenant (EC-08), a secret (RQ-10) or no context, and wakes
 * the dispatcher once per commit (RQ-13). A {@code Denied} after a rollback survives in a short transaction of the
 * producer (docs/decisions.md, D-S8).
 */
class AuditPublisherIT extends OutboxIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-23T08:00:00.123Z"), ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AtomicInteger wakeUps = new AtomicInteger();
    private final OutboxAuditPublisher publisher = new OutboxAuditPublisher(
            new AuditStaging("2.2.0", "verifier", CLOCK), new JdbcOutboxStore(dataSource),
            new ThreadLocalAuditContextAccessor(), wakeUps::incrementAndGet);

    private AuditContextScope request;

    @BeforeEach
    void openTheRequestContext() {
        request = AuditContexts.open(new AuditContext("sandbox", "VATES-B00000002", "op-1",
                AuditActor.person("12345678Z", "María García López", "maria@example.org")));
    }

    @AfterEach
    void closeTheRequestContext() {
        request.close();
    }

    @Test
    void stage_inTheBusinessTransaction_commitsTheEventWithIt() throws Exception {
        UUID business = UUID.randomUUID();

        transaction.executeWithoutResult(status -> {
            insertBusiness(business);
            publisher.stage(AuditDraft.success(revocation().detail("revocation_reason", "KeyCompromise").build()));
        });

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
        UUID eventId = onlyEventId();
        assertThat(row(eventId)).containsEntry("status", "PENDING").containsEntry("tenant", "sandbox")
                .containsEntry("event_type", "Revocation").containsEntry("correlation_id", "op-1");
        JsonNode payload = payload(eventId);
        assertThat(payload.get("event_id").asText()).isEqualTo(eventId.toString());
        assertThat(payload.get("event_version").asText()).isEqualTo("2.2.0");
        assertThat(payload.get("occurred_at").asText()).isEqualTo("2026-10-23T08:00:00.123Z");
        assertThat(payload.get("source").asText()).isEqualTo("verifier");
        assertThat(payload.get("organization").asText()).isEqualTo("VATES-B00000002");
        assertThat(payload.at("/actor/kind").asText()).isEqualTo("Person");
        assertThat(payload.get("result").asText()).isEqualTo("Success");
        assertThat(payload.at("/details/revocation_reason").asText()).isEqualTo("KeyCompromise");
        assertThat(payload.has("recorded_at")).isFalse();
    }

    @Test
    void rollbackOfTheBusiness_discardsTheEvent_andWakesNobody() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertBusiness(UUID.randomUUID());
            publisher.stage(AuditDraft.success(revocation().build()));
            throw new IllegalStateException("business failure");
        })).hasMessage("business failure");

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        assertThat(wakeUps).hasValue(0);
    }

    @Test
    void twoEventsInOneTransaction_haveTheirOwnIds_andWakeTheDispatcherOnceAfterTheCommit() {
        transaction.executeWithoutResult(status -> {
            publisher.stage(AuditDraft.success(revocation().build()));
            publisher.stage(AuditDraft.success(revocation().build()));
            assertThat(wakeUps).as("not before the commit").hasValue(0);
        });

        assertThat(jdbc.sql("SELECT DISTINCT event_id FROM public.audit_outbox").query(UUID.class).list()).hasSize(2);
        assertThat(wakeUps).hasValue(1);
    }

    @Test
    void sourceOfTheFact_overridesTheDefault() throws Exception {
        transaction.executeWithoutResult(status ->
                publisher.stage(AuditDraft.success(revocation().source("status-lists").build())));

        assertThat(payload(onlyEventId()).get("source").asText()).isEqualTo("status-lists");
    }

    @Test
    void withoutATransaction_stageFails_andInsertsNothing() {
        assertThatThrownBy(() -> publisher.stage(AuditDraft.success(revocation().build())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EC-09");

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void withoutAContext_stageFails_andTheBusinessRollsBack() {
        request.close();
        try {
            assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
                insertBusiness(UUID.randomUUID());
                publisher.stage(AuditDraft.success(revocation().build()));
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("audit context");
        } finally {
            request = AuditContexts.open(new AuditContext("sandbox", null, "op-1", AuditActor.system("verifier")));
        }

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
    }

    @Test
    void draftOfAnotherTenant_failsAndRollsTheBusinessBack() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertBusiness(UUID.randomUUID());
            publisher.stage(AuditDraft.success(revocation().tenant("kpmg").build()));
        })).isInstanceOf(AuditTenantMismatchException.class);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void draftWithASecret_failsAndRollsTheBusinessBack() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insertBusiness(UUID.randomUUID());
            publisher.stage(AuditDraft.success(revocation().detail("access_token", "abc").build()));
        })).isInstanceOf(AuditDraftRejectedException.class);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void deniedAfterTheBusinessRolledBack_survivesInAShortTransactionOfTheProducer() throws Exception {
        try {
            transaction.executeWithoutResult(status -> {
                insertBusiness(UUID.randomUUID());
                throw new IllegalStateException("PowerMissing");
            });
        } catch (IllegalStateException denied) {
            transaction.executeWithoutResult(status ->
                    publisher.stage(AuditDraft.denied(revocation().build(), denied.getMessage())));
        }

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        JsonNode payload = payload(onlyEventId());
        assertThat(payload.get("result").asText()).isEqualTo("Denied");
        assertThat(payload.get("reason").asText()).isEqualTo("PowerMissing");
        assertThat(wakeUps).hasValue(1);
    }

    private static AuditFact.Builder revocation() {
        return AuditFact.builder("Revocation").action("Revoke").resource("issuance:0b6f1d2e");
    }

    private void insertBusiness(UUID id) {
        jdbc.sql("SELECT set_config('search_path', 'sandbox', true)").query(String.class).single();
        jdbc.sql("INSERT INTO issuance (id) VALUES (:id)").param("id", id).update();
    }

    private UUID onlyEventId() {
        List<UUID> ids = jdbc.sql("SELECT event_id FROM public.audit_outbox").query(UUID.class).list();
        assertThat(ids).hasSize(1);
        return ids.getFirst();
    }

    private JsonNode payload(UUID eventId) throws Exception {
        return JSON.readTree(jdbc.sql("SELECT CAST(payload AS text) FROM public.audit_outbox WHERE event_id = :id")
                .param("id", eventId).query(String.class).single());
    }
}
