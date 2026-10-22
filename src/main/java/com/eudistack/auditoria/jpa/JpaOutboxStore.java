package com.eudistack.auditoria.jpa;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxLimits;
import com.eudistack.auditoria.domain.OutboxStats;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.sql.OutboxArguments;
import com.eudistack.auditoria.sql.OutboxSql;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@link OutboxStore} on JPA, for servlet producers on Spring Data JPA (DT-002 §3.4.1, AD-3). Native queries with
 * the shared SQL and no {@code @Entity}: the outbox is never mapped, so neither the persistence context nor
 * {@code ddl-auto} can touch it or place it in a tenant schema (R-4).
 *
 * <p>{@link #insert} joins the business transaction through the shared, transaction-aware {@link EntityManager};
 * without one, JPA refuses the statement (EC-09). The dispatcher operations run in a transaction of their own
 * ({@code REQUIRES_NEW}), committed before the HTTP call (AD-4).
 */
public final class JpaOutboxStore implements OutboxStore {

    private final EntityManager entityManager;
    private final TransactionTemplate ownTransaction;

    /**
     * @param entityManager the shared, transaction-aware {@code EntityManager} of the producer
     */
    public JpaOutboxStore(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void insert(OutboxEntry entry) {
        entityManager.createNativeQuery(OutboxSql.INSERT)
                .setParameter("id", UUID.randomUUID())
                .setParameter("event_id", entry.eventId())
                .setParameter("tenant", entry.tenant())
                .setParameter("event_type", entry.eventType())
                .setParameter("correlation_id", entry.correlationId())
                .setParameter("payload", entry.payload())
                .setParameter("traceparent", entry.traceparent())
                .executeUpdate();
    }

    @Override
    public int reclaimExpired(Duration lease) {
        double seconds = OutboxArguments.seconds(lease);
        return update(() -> entityManager.createNativeQuery(OutboxSql.RECLAIM_EXPIRED)
                .setParameter("lease_seconds", seconds)
                .executeUpdate());
    }

    @Override
    public List<ClaimedEvent> claim(int limit, String instanceId) {
        int checkedLimit = OutboxArguments.limit(limit);
        String instance = OutboxArguments.instance(instanceId);
        return ownTransaction.execute(status -> {
            List<?> rows = entityManager.createNativeQuery(OutboxSql.CLAIM)
                    .setParameter("limit", checkedLimit)
                    .setParameter("instance_id", instance)
                    .getResultList();
            return rows.stream().map(row -> claimed((Object[]) row)).toList();
        });
    }

    @Override
    public int delete(Collection<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        return update(() -> entityManager.createNativeQuery(OutboxSql.DELETE)
                .setParameter("event_ids", eventIds)
                .executeUpdate());
    }

    @Override
    public int reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError) {
        if (eventIds.isEmpty()) {
            return 0;
        }
        double seconds = OutboxArguments.seconds(delay);
        String instance = OutboxArguments.instance(instanceId);
        return update(() -> entityManager.createNativeQuery(OutboxSql.RESCHEDULE)
                .setParameter("delay_seconds", seconds)
                .setParameter("last_error", OutboxSql.cut(lastError, OutboxLimits.LAST_ERROR_LENGTH))
                .setParameter("event_ids", eventIds)
                .setParameter("instance_id", instance)
                .executeUpdate());
    }

    @Override
    public boolean quarantine(String instanceId, UUID eventId, String reason) {
        String instance = OutboxArguments.instance(instanceId);
        return update(() -> entityManager.createNativeQuery(OutboxSql.QUARANTINE)
                .setParameter("reason", OutboxSql.cut(reason, OutboxLimits.QUARANTINE_REASON_LENGTH))
                .setParameter("event_id", eventId)
                .setParameter("instance_id", instance)
                .executeUpdate()) == 1;
    }

    @Override
    public int releaseOwned(String instanceId) {
        String instance = OutboxArguments.instance(instanceId);
        return update(() -> entityManager.createNativeQuery(OutboxSql.RELEASE_OWNED)
                .setParameter("instance_id", instance)
                .executeUpdate());
    }

    @Override
    public OutboxStats stats() {
        return ownTransaction.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery(OutboxSql.STATS).getSingleResult();
            Number ageMillis = (Number) row[2];
            return new OutboxStats(((Number) row[0]).longValue(), ((Number) row[1]).longValue(),
                    ageMillis == null ? null : Duration.ofMillis(ageMillis.longValue()));
        });
    }

    private int update(UpdateStatement statement) {
        Integer updated = ownTransaction.execute(status -> statement.run());
        return updated == null ? 0 : updated;
    }

    private static ClaimedEvent claimed(Object[] row) {
        return new ClaimedEvent((UUID) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4],
                ((Number) row[5]).intValue());
    }

    @FunctionalInterface
    private interface UpdateStatement {
        int run();
    }
}
