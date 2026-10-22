package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxStats;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code public.audit_outbox}, reactive variant (R2DBC). Same operations and guarantees as {@link OutboxStore}
 * (DT-002 §3.3 and §3.7.3), as {@code Mono}s: nothing blocks.
 *
 * <p>{@link #insert} runs in the reactive transaction of the subscriber (the business {@code TransactionalOperator}).
 * Every other operation belongs to the dispatcher and must run outside any business transaction, so it commits on
 * its own before the HTTP call (AD-4).
 */
public interface ReactiveOutboxStore {

    /** @see OutboxStore#insert */
    Mono<Void> insert(OutboxEntry entry);

    /** @see OutboxStore#reclaimExpired */
    Mono<Integer> reclaimExpired(Duration lease);

    /** @see OutboxStore#claim */
    Mono<List<ClaimedEvent>> claim(int limit, String instanceId);

    /** @see OutboxStore#delete */
    Mono<Integer> delete(Collection<UUID> eventIds);

    /** @see OutboxStore#reschedule */
    Mono<Integer> reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError);

    /** @see OutboxStore#quarantine */
    Mono<Boolean> quarantine(String instanceId, UUID eventId, String reason);

    /** @see OutboxStore#releaseOwned */
    Mono<Integer> releaseOwned(String instanceId);

    /** @see OutboxStore#stats */
    Mono<OutboxStats> stats();
}
