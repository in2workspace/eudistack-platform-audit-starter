package com.eudistack.auditoria.r2dbc;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxLimits;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.port.ReactiveOutboxStore;
import com.eudistack.auditoria.sql.OutboxArguments;
import com.eudistack.auditoria.sql.OutboxSql;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ReactiveOutboxStore} on R2DBC, for reactive producers (DT-002 §3.4.1). It uses the producer's
 * {@link ConnectionFactory} through Spring's transaction-aware access: inside a {@code TransactionalOperator},
 * {@link #insert} runs on the connection of the business transaction. Nothing blocks, and argument errors are
 * error signals.
 */
public final class R2dbcOutboxStore implements ReactiveOutboxStore {

    private final DatabaseClient client;

    public R2dbcOutboxStore(ConnectionFactory connectionFactory) {
        this.client = DatabaseClient.create(connectionFactory);
    }

    @Override
    public Mono<Void> insert(OutboxEntry entry) {
        return Mono.defer(() -> bindNullable(client.sql(OutboxSql.INSERT)
                        .bind("id", UUID.randomUUID())
                        .bind("event_id", entry.eventId())
                        .bind("tenant", entry.tenant())
                        .bind("event_type", entry.eventType())
                        .bind("correlation_id", entry.correlationId())
                        .bind("payload", entry.payload()), "traceparent", entry.traceparent())
                .fetch().rowsUpdated().then());
    }

    @Override
    public Mono<Integer> reclaimExpired(Duration lease) {
        return Mono.defer(() -> client.sql(OutboxSql.RECLAIM_EXPIRED)
                .bind("lease_seconds", OutboxArguments.seconds(lease))
                .fetch().rowsUpdated().map(Long::intValue));
    }

    @Override
    public Mono<List<ClaimedEvent>> claim(int limit, String instanceId) {
        return Mono.defer(() -> client.sql(OutboxSql.CLAIM)
                .bind("limit", OutboxArguments.limit(limit))
                .bind("instance_id", OutboxArguments.instance(instanceId))
                .map((row, metadata) -> claimed(row))
                .all()
                .collectList());
    }

    @Override
    public Mono<Integer> delete(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return Mono.just(0);
        }
        return client.sql(OutboxSql.DELETE)
                .bind("event_ids", List.copyOf(eventIds))
                .fetch().rowsUpdated().map(Long::intValue);
    }

    @Override
    public Mono<Integer> reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError) {
        if (eventIds.isEmpty()) {
            return Mono.just(0);
        }
        return Mono.defer(() -> bindNullable(client.sql(OutboxSql.RESCHEDULE)
                        .bind("delay_seconds", OutboxArguments.seconds(delay))
                        .bind("event_ids", List.copyOf(eventIds))
                        .bind("instance_id", OutboxArguments.instance(instanceId)),
                "last_error", OutboxSql.cut(lastError, OutboxLimits.LAST_ERROR_LENGTH))
                .fetch().rowsUpdated().map(Long::intValue));
    }

    @Override
    public Mono<Boolean> quarantine(String instanceId, UUID eventId, String reason) {
        return Mono.defer(() -> bindNullable(client.sql(OutboxSql.QUARANTINE)
                        .bind("event_id", eventId)
                        .bind("instance_id", OutboxArguments.instance(instanceId)),
                "reason", OutboxSql.cut(reason, OutboxLimits.QUARANTINE_REASON_LENGTH))
                .fetch().rowsUpdated().map(updated -> updated == 1));
    }

    @Override
    public Mono<Integer> releaseOwned(String instanceId) {
        return Mono.defer(() -> client.sql(OutboxSql.RELEASE_OWNED)
                .bind("instance_id", OutboxArguments.instance(instanceId))
                .fetch().rowsUpdated().map(Long::intValue));
    }

    @Override
    public Mono<OutboxStats> stats() {
        return client.sql(OutboxSql.STATS)
                .map((row, metadata) -> {
                    Long ageMillis = row.get("oldest_pending_age_ms", Long.class);
                    return new OutboxStats(
                            Objects.requireNonNull(row.get("pending", Long.class)),
                            Objects.requireNonNull(row.get("quarantined", Long.class)),
                            ageMillis == null ? null : Duration.ofMillis(ageMillis));
                })
                .one();
    }

    private static ClaimedEvent claimed(Row row) {
        return new ClaimedEvent(
                row.get("event_id", UUID.class),
                row.get("tenant", String.class),
                row.get("event_type", String.class),
                row.get("payload", String.class),
                row.get("traceparent", String.class),
                Objects.requireNonNull(row.get("attempts", Integer.class)));
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
