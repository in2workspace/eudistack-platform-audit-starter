package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * DT-002 §2.3 {@code OutboxSkipLockedIT} (RQ-12, EC-01): while one instance holds its claimed rows locked, another
 * claims the rest without waiting, and no row goes to both.
 */
class OutboxSkipLockedIT extends OutboxIntegrationTest {

    private final JdbcOutboxStore store = new JdbcOutboxStore(dataSource);

    @Test
    void concurrentClaims_takeDisjointRows_withoutBlocking() throws Exception {
        List<UUID> ids = insertPending(5, store::insert);
        CountDownLatch claimedByA = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicReference<List<ClaimedEvent>> podA = new AtomicReference<>();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // pod-a claims inside a transaction it keeps open: its rows stay locked until it commits.
            Future<?> podATransaction = executor.submit(() -> transaction.executeWithoutResult(status -> {
                podA.set(store.claim(2, "pod-a"));
                claimedByA.countDown();
                awaitQuietly(releaseA);
            }));
            assertThat(claimedByA.await(10, TimeUnit.SECONDS)).isTrue();

            List<ClaimedEvent> podB = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> store.claim(100, "pod-b"));

            releaseA.countDown();
            podATransaction.get(10, TimeUnit.SECONDS);

            List<UUID> claimedByPodA = podA.get().stream().map(ClaimedEvent::eventId).toList();
            List<UUID> claimedByPodB = podB.stream().map(ClaimedEvent::eventId).toList();
            assertThat(claimedByPodA).hasSize(2).doesNotContainAnyElementsOf(claimedByPodB);
            assertThat(claimedByPodB).hasSize(3);
            assertThat(claimedByPodA).as("every row claimed once").containsAll(
                    ids.stream().filter(id -> !claimedByPodB.contains(id)).toList());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
