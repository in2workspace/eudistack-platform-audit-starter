package com.eudistack.auditoria.support;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.jdbc.JdbcOutboxStore;
import com.eudistack.auditoria.port.OutboxStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every outbox store has to do, whatever runs the SQL (DT-002 AD-3: one SQL, three runners). JDBC, R2DBC and
 * JPA each provide the store and their own business transaction.
 *
 * <p>The business transaction tests are the {@code *OutboxStoreIT} of DT-002 §2.3: the row is committed or rolled
 * back with the business change (RQ-01, EC-10) and lands in {@code public} whatever the tenant's search_path (EC-13).
 * The rest checks the dispatcher operations of §3.7.3.
 */
public abstract class OutboxStoreContract extends OutboxIntegrationTest {

    private final JdbcOutboxStore fixture = new JdbcOutboxStore(dataSource);

    protected abstract OutboxStore store();

    /**
     * In one business transaction of tenant {@code sandbox} (its schema on the search_path): inserts
     * {@code businessId} in {@code issuance}, stages {@code entry} with {@link #store()}, then runs
     * {@code beforeCommit}, which throws to roll everything back.
     */
    protected abstract void inBusinessTransaction(UUID businessId, OutboxEntry entry, Runnable beforeCommit);

    /** Rows set up for a test, inserted outside the store under test. */
    private List<UUID> pending(int count) {
        return insertPending(count, fixture::insert);
    }

    @Test
    void businessTransaction_commit_keepsTheBusinessChangeAndTheRow_inPublic() {
        UUID event = UUID.randomUUID();

        inBusinessTransaction(UUID.randomUUID(), entry(event), () -> { });

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
        assertThat(row(event)).containsEntry("status", "PENDING").containsEntry("tenant", "sandbox")
                .containsEntry("attempts", 0).containsEntry("locked_by", null);
        assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_outbox'"))
                .isEqualTo(1);
    }

    @Test
    void businessTransaction_rollback_discardsTheBusinessChangeAndTheRow() {
        assertThatThrownBy(() -> inBusinessTransaction(UUID.randomUUID(), entry(UUID.randomUUID()), () -> {
            throw new IllegalStateException("business failure");
        })).hasMessageContaining("business failure");

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void businessTransaction_failingInsert_rollsTheBusinessBack() {
        UUID event = UUID.randomUUID();
        fixture.insert(entry(event));

        assertThatThrownBy(() -> inBusinessTransaction(UUID.randomUUID(), entry(event), () -> { }))
                .isInstanceOf(RuntimeException.class);

        assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
    }

    @Test
    void insert_storesThePayloadAndTheTraceparent() throws Exception {
        UUID event = UUID.randomUUID();
        String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        inBusinessTransaction(UUID.randomUUID(), new OutboxEntry(event, "sandbox", "IssuanceCompleted", "op-1",
                "{\"event_id\": \"" + event + "\", \"details\": {\"b\": 1, \"a\": [true]}}", traceparent), () -> { });

        ClaimedEvent claimed = store().claim(1, "pod-a").getFirst();

        ObjectMapper json = new ObjectMapper();
        assertThat(json.readTree(claimed.payload()))
                .isEqualTo(json.readTree("{\"details\": {\"a\": [true], \"b\": 1}, \"event_id\": \"" + event + "\"}"));
        assertThat(claimed.traceparent()).isEqualTo(traceparent);
        assertThat(claimed.eventType()).isEqualTo("IssuanceCompleted");
        assertThat(claimed.tenant()).isEqualTo("sandbox");
    }

    @Test
    void insert_withoutTraceparent_storesNull() {
        UUID event = UUID.randomUUID();

        inBusinessTransaction(UUID.randomUUID(), entry(event), () -> { });

        assertThat(row(event)).containsEntry("traceparent", null);
    }

    @Test
    void claim_takesTheOldestDueRows_upToTheLimit_andLocksThem() {
        List<UUID> ids = pending(3);

        List<ClaimedEvent> claimed = store().claim(2, "pod-a");

        assertThat(claimed).extracting(ClaimedEvent::eventId).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
        assertThat(claimed).allSatisfy(event -> assertThat(event.attempts()).isEqualTo(1));
        assertThat(row(ids.get(0))).containsEntry("status", "IN_FLIGHT").containsEntry("locked_by", "pod-a");
        assertThat(row(ids.get(0)).get("locked_at")).isNotNull();
        assertThat(row(ids.get(2))).containsEntry("status", "PENDING");
    }

    @Test
    void claim_skipsRowsNotDueYet() {
        List<UUID> ids = pending(2);
        jdbc.sql("UPDATE public.audit_outbox SET next_attempt_at = clock_timestamp() + interval '1 hour' "
                + "WHERE event_id = :id").param("id", ids.get(0)).update();

        assertThat(store().claim(100, "pod-a")).extracting(ClaimedEvent::eventId).containsExactly(ids.get(1));
    }

    @Test
    void claim_emptyOutbox_claimsNothing() {
        assertThat(store().claim(100, "pod-a")).isEmpty();
    }

    @Test
    void claim_limitOutsideTheContractOrMissingInstance_isRejected() {
        assertThatThrownBy(() -> store().claim(0, "pod-a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().claim(101, "pod-a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store().claim(10, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reclaimExpired_returnsOnlyTheRowsWhoseLeaseExpired() {
        List<UUID> ids = pending(2);
        fixture.claim(2, "pod-dead");
        jdbc.sql("UPDATE public.audit_outbox SET locked_at = clock_timestamp() - interval '31 seconds' "
                + "WHERE event_id = :id").param("id", ids.get(0)).update();

        assertThat(store().reclaimExpired(Duration.ofSeconds(30))).isEqualTo(1);

        assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null);
        assertThat(row(ids.get(1))).containsEntry("status", "IN_FLIGHT");
    }

    @Test
    void delete_removesTheAnsweredRows() {
        List<UUID> ids = pending(3);
        fixture.claim(3, "pod-a");

        assertThat(store().delete(List.of(ids.get(0), ids.get(1)))).isEqualTo(2);
        assertThat(store().delete(List.of())).isZero();
        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
    }

    @Test
    void reschedule_onlyTouchesTheOwnInFlightRows_andDelaysThem() {
        List<UUID> ids = pending(2);
        fixture.claim(2, "pod-a");

        assertThat(store().reschedule("pod-b", ids, Duration.ofSeconds(60), "HTTP 503")).isZero();
        assertThat(store().reschedule("pod-a", ids, Duration.ofSeconds(60), "x".repeat(600))).isEqualTo(2);

        assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null)
                .containsEntry("locked_at", null);
        assertThat((String) row(ids.get(0)).get("last_error")).hasSize(500);
        double delaySeconds = jdbc.sql("SELECT EXTRACT(EPOCH FROM next_attempt_at - clock_timestamp()) "
                + "FROM public.audit_outbox WHERE event_id = :id").param("id", ids.get(0))
                .query(Double.class).single();
        assertThat(delaySeconds).isBetween(55.0, 60.0);
        assertThat(store().claim(100, "pod-a")).isEmpty();
    }

    @Test
    void reschedule_withoutError_storesNull() {
        List<UUID> ids = pending(1);
        fixture.claim(1, "pod-a");

        assertThat(store().reschedule("pod-a", ids, Duration.ZERO, null)).isEqualTo(1);

        assertThat(row(ids.getFirst())).containsEntry("last_error", null).containsEntry("status", "PENDING");
    }

    @Test
    void quarantine_onlyTheOwnInFlightRow_andTheClaimNeverTakesItAgain() {
        UUID id = pending(1).getFirst();
        fixture.claim(1, "pod-a");

        assertThat(store().quarantine("pod-b", id, "400 BAD_REQUEST")).isFalse();
        assertThat(store().quarantine("pod-a", id, "400 BAD_REQUEST")).isTrue();

        assertThat(row(id)).containsEntry("status", "QUARANTINED").containsEntry("quarantine_reason",
                "400 BAD_REQUEST").containsEntry("locked_by", null);
        assertThat(row(id).get("quarantined_at")).isNotNull();
        assertThat(store().releaseOwned("pod-a")).isZero();
        assertThat(store().reclaimExpired(Duration.ZERO)).isZero();
        assertThat(store().claim(100, "pod-a")).isEmpty();
    }

    @Test
    void releaseOwned_onlyReleasesTheRowsOfTheInstance() {
        List<UUID> ids = pending(3);
        fixture.claim(2, "pod-a");
        fixture.claim(1, "pod-b");

        assertThat(store().releaseOwned("pod-a")).isEqualTo(2);

        assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null);
        assertThat(row(ids.get(2))).containsEntry("status", "IN_FLIGHT").containsEntry("locked_by", "pod-b");
    }

    @Test
    void stats_emptyOutbox() {
        assertThat(store().stats()).isEqualTo(OutboxStats.EMPTY);
    }

    @Test
    void stats_countPendingAndInFlight_quarantined_andTheOldestAge() {
        List<UUID> ids = pending(4);
        fixture.claim(1, "pod-a");
        jdbc.sql("UPDATE public.audit_outbox SET status = 'QUARANTINED' WHERE event_id = :id")
                .param("id", ids.get(3)).update();
        jdbc.sql("UPDATE public.audit_outbox SET created_at = clock_timestamp() - interval '10 minutes' "
                + "WHERE event_id = :id").param("id", ids.get(1)).update();

        OutboxStats stats = store().stats();

        assertThat(stats.pending()).isEqualTo(3);
        assertThat(stats.quarantined()).isEqualTo(1);
        assertThat(stats.oldestPendingAge()).isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11));
    }
}
