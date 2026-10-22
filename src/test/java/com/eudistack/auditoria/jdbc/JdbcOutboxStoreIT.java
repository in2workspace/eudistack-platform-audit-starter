package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §2.3 {@code JdbcOutboxStoreIT}: the row is committed or rolled back with the business change (RQ-01,
 * EC-10), lands in {@code public} whatever the tenant's search_path (EC-13), and the dispatcher operations of
 * §3.7.3 only touch the rows they should.
 */
class JdbcOutboxStoreIT extends OutboxIntegrationTest {

    private final JdbcOutboxStore store = new JdbcOutboxStore(dataSource);

    @Nested
    class InsertInTheBusinessTransaction {

        @Test
        void commit_keepsTheBusinessChangeAndTheRow_inPublic() {
            UUID business = UUID.randomUUID();
            UUID event = UUID.randomUUID();

            inSandboxTransaction(tenant -> {
                tenant.sql("INSERT INTO issuance (id) VALUES (:id)").param("id", business).update();
                store.insert(entry(event));
            });

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            assertThat(row(event)).containsEntry("status", "PENDING").containsEntry("tenant", "sandbox")
                    .containsEntry("attempts", 0).containsEntry("locked_by", null);
            assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_outbox'"))
                    .isEqualTo(1);
        }

        @Test
        void rollback_discardsTheBusinessChangeAndTheRow() {
            assertThatThrownBy(() -> inSandboxTransaction(tenant -> {
                tenant.sql("INSERT INTO issuance (id) VALUES (:id)").param("id", UUID.randomUUID()).update();
                store.insert(entry(UUID.randomUUID()));
                throw new IllegalStateException("business failure");
            })).hasMessage("business failure");

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        }

        @Test
        void failingInsert_rollsTheBusinessBack() {
            UUID event = UUID.randomUUID();
            store.insert(entry(event));

            assertThatThrownBy(() -> inSandboxTransaction(tenant -> {
                tenant.sql("INSERT INTO issuance (id) VALUES (:id)").param("id", UUID.randomUUID()).update();
                store.insert(entry(event));
            })).isInstanceOf(DuplicateKeyException.class);

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
        }

        @Test
        void payloadAndTraceparent_areStored() throws Exception {
            UUID event = UUID.randomUUID();
            String traceparent = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
            store.insert(new OutboxEntry(event, "sandbox", "IssuanceCompleted",
                    "op-1", "{\"event_id\": \"" + event + "\", \"details\": {\"b\": 1, \"a\": [true]}}", traceparent));

            ClaimedEvent claimed = store.claim(1, "pod-a").getFirst();

            ObjectMapper json = new ObjectMapper();
            assertThat(json.readTree(claimed.payload()))
                    .isEqualTo(json.readTree("{\"details\": {\"a\": [true], \"b\": 1}, \"event_id\": \"" + event + "\"}"));
            assertThat(claimed.traceparent()).isEqualTo(traceparent);
            assertThat(claimed.eventType()).isEqualTo("IssuanceCompleted");
        }
    }

    @Nested
    class Claim {

        @Test
        void takesTheOldestDueRows_upToTheLimit_andLocksThem() {
            List<UUID> ids = insertPending(3, store::insert);

            List<ClaimedEvent> claimed = store.claim(2, "pod-a");

            assertThat(claimed).extracting(ClaimedEvent::eventId).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
            assertThat(claimed).allSatisfy(event -> assertThat(event.attempts()).isEqualTo(1));
            assertThat(row(ids.get(0))).containsEntry("status", "IN_FLIGHT").containsEntry("locked_by", "pod-a");
            assertThat(row(ids.get(0)).get("locked_at")).isNotNull();
            assertThat(row(ids.get(2))).containsEntry("status", "PENDING");
        }

        @Test
        void skipsRowsNotDueYet() {
            List<UUID> ids = insertPending(2, store::insert);
            jdbc.sql("UPDATE public.audit_outbox SET next_attempt_at = clock_timestamp() + interval '1 hour' "
                    + "WHERE event_id = :id").param("id", ids.get(0)).update();

            assertThat(store.claim(100, "pod-a")).extracting(ClaimedEvent::eventId).containsExactly(ids.get(1));
        }

        @Test
        void emptyOutbox_claimsNothing() {
            assertThat(store.claim(100, "pod-a")).isEmpty();
        }

        @Test
        void limitOutsideTheContractOrMissingInstance_isRejected() {
            assertThatThrownBy(() -> store.claim(0, "pod-a")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.claim(101, "pod-a")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.claim(10, " ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class AfterTheResponse {

        @Test
        void delete_removesTheAnsweredRows() {
            List<UUID> ids = insertPending(3, store::insert);
            store.claim(3, "pod-a");

            assertThat(store.delete(List.of(ids.get(0), ids.get(1)))).isEqualTo(2);
            assertThat(store.delete(List.of())).isZero();
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(1);
        }

        @Test
        void reschedule_onlyTouchesTheOwnInFlightRows_andDelaysThem() {
            List<UUID> ids = insertPending(2, store::insert);
            store.claim(2, "pod-a");

            assertThat(store.reschedule("pod-b", ids, Duration.ofSeconds(60), "HTTP 503")).isZero();
            assertThat(store.reschedule("pod-a", ids, Duration.ofSeconds(60), "x".repeat(600))).isEqualTo(2);

            assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null)
                    .containsEntry("locked_at", null);
            assertThat((String) row(ids.get(0)).get("last_error")).hasSize(500);
            double delaySeconds = jdbc.sql("SELECT EXTRACT(EPOCH FROM next_attempt_at - clock_timestamp()) "
                    + "FROM public.audit_outbox WHERE event_id = :id").param("id", ids.get(0))
                    .query(Double.class).single();
            assertThat(delaySeconds).isBetween(55.0, 60.0);
            assertThat(store.claim(100, "pod-a")).isEmpty();
        }

        @Test
        void quarantine_onlyTheOwnInFlightRow_andTheClaimNeverTakesItAgain() {
            UUID id = insertPending(1, store::insert).getFirst();
            store.claim(1, "pod-a");

            assertThat(store.quarantine("pod-b", id, "400 BAD_REQUEST")).isFalse();
            assertThat(store.quarantine("pod-a", id, "400 BAD_REQUEST")).isTrue();

            assertThat(row(id)).containsEntry("status", "QUARANTINED").containsEntry("quarantine_reason",
                    "400 BAD_REQUEST").containsEntry("locked_by", null);
            assertThat(row(id).get("quarantined_at")).isNotNull();
            assertThat(store.releaseOwned("pod-a")).isZero();
            assertThat(store.reclaimExpired(Duration.ZERO)).isZero();
            assertThat(store.claim(100, "pod-a")).isEmpty();
        }

        @Test
        void releaseOwned_onlyReleasesTheRowsOfTheInstance() {
            List<UUID> ids = insertPending(3, store::insert);
            store.claim(2, "pod-a");
            store.claim(1, "pod-b");

            assertThat(store.releaseOwned("pod-a")).isEqualTo(2);

            assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null);
            assertThat(row(ids.get(2))).containsEntry("status", "IN_FLIGHT").containsEntry("locked_by", "pod-b");
        }
    }

    @Nested
    class Stats {

        @Test
        void emptyOutbox() {
            assertThat(store.stats()).isEqualTo(OutboxStats.EMPTY);
        }

        @Test
        void countsPendingAndInFlight_quarantined_andTheOldestAge() {
            List<UUID> ids = insertPending(4, store::insert);
            store.claim(1, "pod-a");
            jdbc.sql("UPDATE public.audit_outbox SET status = 'QUARANTINED' WHERE event_id = :id")
                    .param("id", ids.get(3)).update();
            jdbc.sql("UPDATE public.audit_outbox SET created_at = clock_timestamp() - interval '10 minutes' "
                    + "WHERE event_id = :id").param("id", ids.get(1)).update();

            OutboxStats stats = store.stats();

            assertThat(stats.pending()).isEqualTo(3);
            assertThat(stats.quarantined()).isEqualTo(1);
            assertThat(stats.oldestPendingAge()).isBetween(Duration.ofMinutes(10), Duration.ofMinutes(11));
        }
    }
}
