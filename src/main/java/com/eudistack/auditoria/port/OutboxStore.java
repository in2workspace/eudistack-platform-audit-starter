package com.eudistack.auditoria.port;

import com.eudistack.auditoria.domain.ClaimedEvent;
import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.domain.OutboxStats;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * {@code public.audit_outbox}, servlet variant (JDBC or JPA). DT-002 §3.3 and §3.7.3. Every statement names the
 * table with its schema, whatever the {@code search_path} of the connection (EC-13), and takes its time from
 * the database clock, so pods with different clocks agree.
 *
 * <p>{@link #insert} runs in the caller's transaction: the business transaction. Every other operation belongs
 * to the dispatcher and must run outside any business transaction, so it commits on its own before the HTTP
 * call (AD-4).
 */
public interface OutboxStore {

    /** Inserts a {@code PENDING} row in the current transaction. A failure rolls the business back (EC-10). */
    void insert(OutboxEntry entry);

    /**
     * Returns to {@code PENDING} the {@code IN_FLIGHT} rows locked longer than {@code lease} ago, left by an
     * instance that died (EC-02).
     *
     * @return the number of rows returned
     */
    int reclaimExpired(Duration lease);

    /**
     * Claims up to {@code limit} {@code PENDING} rows due now, oldest first, with {@code FOR UPDATE SKIP LOCKED},
     * so instances claim different rows (EC-01). They become {@code IN_FLIGHT}, locked by {@code instanceId}, with
     * one more attempt.
     *
     * @param limit 1 to {@link com.eudistack.auditoria.domain.OutboxLimits#MAX_BATCH_SIZE}
     */
    List<ClaimedEvent> claim(int limit, String instanceId);

    /**
     * Deletes the rows the Audit API answered as {@code accepted}, {@code duplicate} or {@code rejected} (AD-2).
     *
     * @return the number of rows deleted
     */
    int delete(Collection<UUID> eventIds);

    /**
     * Returns to {@code PENDING}, due after {@code delay}, the given rows that are still {@code IN_FLIGHT} and
     * locked by {@code instanceId}: rows another instance reclaimed meanwhile are left alone.
     *
     * @param lastError what went wrong (HTTP status, error type), never the payload; cut to
     *                  {@link com.eudistack.auditoria.domain.OutboxLimits#LAST_ERROR_LENGTH}
     * @return the number of rows rescheduled
     */
    int reschedule(String instanceId, Collection<UUID> eventIds, Duration delay, String lastError);

    /**
     * Moves to {@code QUARANTINED} the row that the Audit API rejects with 400 on its own (AD-9), if it is still
     * {@code IN_FLIGHT} and locked by {@code instanceId}.
     *
     * @param reason cut to {@link com.eudistack.auditoria.domain.OutboxLimits#QUARANTINE_REASON_LENGTH}
     * @return whether the row was quarantined
     */
    boolean quarantine(String instanceId, UUID eventId, String reason);

    /**
     * Returns to {@code PENDING} every {@code IN_FLIGHT} row of {@code instanceId}, at shutdown (EC-16).
     *
     * @return the number of rows released
     */
    int releaseOwned(String instanceId);

    OutboxStats stats();
}
