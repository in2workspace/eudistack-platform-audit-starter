package com.eudistack.auditoria.domain;

/**
 * Limits of {@code public.audit_outbox} and of the Audit API contract that the outbox has to respect.
 */
public final class OutboxLimits {

    /** Largest batch of the Audit API contract ({@code EventBatch.maxItems}): no configuration can raise it. */
    public static final int MAX_BATCH_SIZE = 100;

    static final int TENANT_LENGTH = 100;
    static final int EVENT_TYPE_LENGTH = 100;
    static final int CORRELATION_ID_LENGTH = 200;
    static final int TRACEPARENT_LENGTH = 128;

    /** {@code last_error}: longer texts are cut. */
    public static final int LAST_ERROR_LENGTH = 500;

    /** {@code quarantine_reason}: longer texts are cut. */
    public static final int QUARANTINE_REASON_LENGTH = 200;

    private OutboxLimits() {
    }
}
