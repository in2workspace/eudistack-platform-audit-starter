package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §2.3 {@code OutboxLeaseReclaimIT} (EC-02): an {@code IN_FLIGHT} row whose lease expired, left by an
 * instance that died, goes back to {@code PENDING} and is sent again with the same {@code event_id}; a row still
 * within its lease is left alone.
 */
class OutboxLeaseReclaimIT extends OutboxIntegrationTest {

    private final JdbcOutboxStore store = new JdbcOutboxStore(dataSource);

    @Test
    void expiredLease_returnsTheRowToPending_andItIsClaimedAgain() {
        List<UUID> ids = insertPending(2, store::insert);
        store.claim(2, "pod-dead");
        jdbc.sql("UPDATE public.audit_outbox SET locked_at = clock_timestamp() - interval '31 seconds' "
                + "WHERE event_id = :id").param("id", ids.get(0)).update();

        assertThat(store.reclaimExpired(Duration.ofSeconds(30))).isEqualTo(1);

        assertThat(row(ids.get(0))).containsEntry("status", "PENDING").containsEntry("locked_by", null)
                .containsEntry("locked_at", null);
        assertThat(row(ids.get(1))).containsEntry("status", "IN_FLIGHT").containsEntry("locked_by", "pod-dead");

        List<ClaimedEvent> again = store.claim(100, "pod-alive");
        assertThat(again).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(ids.get(0));
            assertThat(event.attempts()).isEqualTo(2);
        });
    }
}
