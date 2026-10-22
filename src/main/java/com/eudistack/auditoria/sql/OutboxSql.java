package com.eudistack.auditoria.sql;

/**
 * The SQL of {@code public.audit_outbox} (DT-002 §3.7.3), shared by the JDBC, R2DBC and JPA adapters (AD-3: one
 * SQL, three runners). Named parameters ({@code :event_id}) and {@code CAST(... AS ...)} rather than {@code ::},
 * which Hibernate would read as a parameter in a native query. Constants, so they can be the value of a JPA
 * {@code @Query}.
 *
 * <p>Every statement names the table with its schema (EC-13) and takes its time from {@code clock_timestamp()}.
 */
public final class OutboxSql {

    /** Classpath location of the DDL. */
    public static final String SCHEMA_SCRIPT = "db/audit-outbox/V1__public_audit_outbox.sql";

    /** True when both the table and its index exist: the initializer then does nothing, not even lock. */
    public static final String SCHEMA_PRESENT = """
            SELECT to_regclass('public.audit_outbox') IS NOT NULL
               AND to_regclass('public.audit_outbox_pending_idx') IS NOT NULL
            """;

    /**
     * Serializes the initializers of instances that start at the same time: concurrent
     * {@code CREATE TABLE IF NOT EXISTS} can still collide in PostgreSQL. Released at commit.
     */
    public static final String SCHEMA_LOCK = "SELECT pg_advisory_xact_lock(hashtext('public.audit_outbox'))";

    public static final String INSERT = """
            INSERT INTO public.audit_outbox (id, event_id, tenant, event_type, correlation_id, payload, traceparent)
            VALUES (:id, :event_id, :tenant, :event_type, :correlation_id, CAST(:payload AS jsonb), :traceparent)
            """;

    public static final String RECLAIM_EXPIRED = """
            UPDATE public.audit_outbox
            SET status = 'PENDING',
                locked_at = NULL,
                locked_by = NULL,
                next_attempt_at = clock_timestamp()
            WHERE status = 'IN_FLIGHT'
              AND locked_at < clock_timestamp() - make_interval(secs => :lease_seconds)
            """;

    public static final String CLAIM = """
            WITH claimed AS (
                SELECT id
                FROM public.audit_outbox
                WHERE status = 'PENDING'
                  AND next_attempt_at <= clock_timestamp()
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE public.audit_outbox AS o
            SET status = 'IN_FLIGHT',
                locked_at = clock_timestamp(),
                locked_by = :instance_id,
                attempts = o.attempts + 1
            FROM claimed AS c
            WHERE o.id = c.id
            RETURNING o.event_id, o.tenant, o.event_type, CAST(o.payload AS text) AS payload, o.traceparent, o.attempts
            """;

    public static final String DELETE = "DELETE FROM public.audit_outbox WHERE event_id IN (:event_ids)";

    public static final String RESCHEDULE = """
            UPDATE public.audit_outbox
            SET status = 'PENDING',
                locked_at = NULL,
                locked_by = NULL,
                next_attempt_at = clock_timestamp() + make_interval(secs => :delay_seconds),
                last_error = :last_error
            WHERE event_id IN (:event_ids)
              AND status = 'IN_FLIGHT'
              AND locked_by = :instance_id
            """;

    public static final String QUARANTINE = """
            UPDATE public.audit_outbox
            SET status = 'QUARANTINED',
                quarantined_at = clock_timestamp(),
                quarantine_reason = :reason,
                locked_at = NULL,
                locked_by = NULL
            WHERE event_id = :event_id
              AND status = 'IN_FLIGHT'
              AND locked_by = :instance_id
            """;

    public static final String RELEASE_OWNED = """
            UPDATE public.audit_outbox
            SET status = 'PENDING',
                locked_at = NULL,
                locked_by = NULL,
                next_attempt_at = clock_timestamp()
            WHERE status = 'IN_FLIGHT'
              AND locked_by = :instance_id
            """;

    public static final String STATS = """
            SELECT count(*) FILTER (WHERE status IN ('PENDING', 'IN_FLIGHT')) AS pending,
                   count(*) FILTER (WHERE status = 'QUARANTINED') AS quarantined,
                   CAST(EXTRACT(EPOCH FROM clock_timestamp()
                        - min(created_at) FILTER (WHERE status IN ('PENDING', 'IN_FLIGHT'))) * 1000 AS bigint)
                       AS oldest_pending_age_ms
            FROM public.audit_outbox
            """;

    private OutboxSql() {
    }

    /** {@code value} cut to {@code length} characters, for the bounded text columns. */
    public static String cut(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
