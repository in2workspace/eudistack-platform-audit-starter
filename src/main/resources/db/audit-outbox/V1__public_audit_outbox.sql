-- Outbox of audit events of this producer (DT-002 §3.7.3, AD-1). One table in schema public, with a tenant
-- column, even though the business data is schema-per-tenant: never run this script in the per-tenant
-- migration loop (R-1). Idempotent, and valid as a Flyway migration, a Liquibase changelog or the batch the
-- starter's initializer runs once on public.
CREATE TABLE IF NOT EXISTS public.audit_outbox (
    id                 UUID         PRIMARY KEY,
    event_id           UUID         NOT NULL,
    tenant             VARCHAR(100) NOT NULL,
    event_type         VARCHAR(100) NOT NULL,
    correlation_id     VARCHAR(200) NOT NULL,
    payload            JSONB        NOT NULL,
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts           INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    locked_at          TIMESTAMPTZ  NULL,
    locked_by          VARCHAR(200) NULL,
    last_error         VARCHAR(500) NULL,
    traceparent        VARCHAR(128) NULL,
    quarantined_at     TIMESTAMPTZ  NULL,
    quarantine_reason  VARCHAR(200) NULL,
    CONSTRAINT audit_outbox_status_chk CHECK (status IN ('PENDING', 'IN_FLIGHT', 'QUARANTINED')),
    CONSTRAINT audit_outbox_event_id_uk UNIQUE (event_id)
);

CREATE INDEX IF NOT EXISTS audit_outbox_pending_idx
    ON public.audit_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';
