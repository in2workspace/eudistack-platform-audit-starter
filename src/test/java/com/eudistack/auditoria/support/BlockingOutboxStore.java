package com.eudistack.auditoria.support;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.port.ReactiveOutboxStore;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Test-only view of a {@link ReactiveOutboxStore} as an {@link OutboxStore}, so the three adapters run the same
 * contract. Production code never blocks on the reactive store.
 */
public final class BlockingOutboxStore implements OutboxStore {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ReactiveOutboxStore store;

    public BlockingOutboxStore(ReactiveOutboxStore store) {
        this.store = store;
    }

    @Override
    public void insert(OutboxEntry entry) {
        store.insert(entry).block(TIMEOUT);
    }

    @Override
    public int reclaimExpired(Duration lease) {
        return store.reclaimExpired(lease).block(TIMEOUT);
    }

    @Override
    public List<ClaimedEvent> claim(int limit, String instanceId) {
        return store.claim(limit, instanceId).block(TIMEOUT);
    }

    @Override
    public int delete(Collection<UUID> eventIds) {
        return store.delete(eventIds).block(TIMEOUT);
    }

    @Override
    public int reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError) {
        return store.reschedule(instanceId, eventIds, delay, lastError).block(TIMEOUT);
    }

    @Override
    public boolean quarantine(String instanceId, UUID eventId, String reason) {
        return store.quarantine(instanceId, eventId, reason).block(TIMEOUT);
    }

    @Override
    public int releaseOwned(String instanceId) {
        return store.releaseOwned(instanceId).block(TIMEOUT);
    }

    @Override
    public OutboxStats stats() {
        return store.stats().block(TIMEOUT);
    }
}
