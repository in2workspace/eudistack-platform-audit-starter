package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxLimits;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.sql.OutboxArguments;
import com.eudistack.auditoria.sql.OutboxSql;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link OutboxStore} on JDBC, for servlet producers (DT-002 §3.4.1). It uses the producer's {@link DataSource}
 * through Spring's transaction-aware access: inside a {@code @Transactional} method, {@link #insert} runs on the
 * connection of the business transaction.
 */
public final class JdbcOutboxStore implements OutboxStore {

    private final JdbcClient jdbc;

    public JdbcOutboxStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void insert(OutboxEntry entry) {
        jdbc.sql(OutboxSql.INSERT)
                .param("id", UUID.randomUUID())
                .param("event_id", entry.eventId())
                .param("tenant", entry.tenant())
                .param("event_type", entry.eventType())
                .param("correlation_id", entry.correlationId())
                .param("payload", entry.payload())
                .param("traceparent", entry.traceparent(), Types.VARCHAR)
                .update();
    }

    @Override
    public int reclaimExpired(Duration lease) {
        return jdbc.sql(OutboxSql.RECLAIM_EXPIRED)
                .param("lease_seconds", OutboxArguments.seconds(lease))
                .update();
    }

    @Override
    public List<ClaimedEvent> claim(int limit, String instanceId) {
        return jdbc.sql(OutboxSql.CLAIM)
                .param("limit", OutboxArguments.limit(limit))
                .param("instance_id", OutboxArguments.instance(instanceId))
                .query(JdbcOutboxStore::claimed)
                .list();
    }

    @Override
    public int delete(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql(OutboxSql.DELETE)
                .param("event_ids", eventIds)
                .update();
    }

    @Override
    public int reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql(OutboxSql.RESCHEDULE)
                .param("delay_seconds", OutboxArguments.seconds(delay))
                .param("last_error", OutboxSql.cut(lastError, OutboxLimits.LAST_ERROR_LENGTH), Types.VARCHAR)
                .param("event_ids", eventIds)
                .param("instance_id", OutboxArguments.instance(instanceId))
                .update();
    }

    @Override
    public boolean quarantine(String instanceId, UUID eventId, String reason) {
        return jdbc.sql(OutboxSql.QUARANTINE)
                .param("reason", OutboxSql.cut(reason, OutboxLimits.QUARANTINE_REASON_LENGTH), Types.VARCHAR)
                .param("event_id", eventId)
                .param("instance_id", OutboxArguments.instance(instanceId))
                .update() == 1;
    }

    @Override
    public int releaseOwned(String instanceId) {
        return jdbc.sql(OutboxSql.RELEASE_OWNED)
                .param("instance_id", OutboxArguments.instance(instanceId))
                .update();
    }

    @Override
    public OutboxStats stats() {
        return jdbc.sql(OutboxSql.STATS)
                .query((rs, row) -> {
                    long ageMillis = rs.getLong("oldest_pending_age_ms");
                    Duration oldest = rs.wasNull() ? null : Duration.ofMillis(ageMillis);
                    return new OutboxStats(rs.getLong("pending"), rs.getLong("quarantined"), oldest);
                })
                .single();
    }

    private static ClaimedEvent claimed(ResultSet rs, int row) throws SQLException {
        return new ClaimedEvent(
                rs.getObject("event_id", UUID.class),
                rs.getString("tenant"),
                rs.getString("event_type"),
                rs.getString("payload"),
                rs.getString("traceparent"),
                rs.getInt("attempts"));
    }

}
