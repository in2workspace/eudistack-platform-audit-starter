package com.eudistack.auditoria.domain;

import java.time.Duration;

/**
 * Snapshot of the outbox for the gauges and the health indicator (DT-002 §3.8).
 *
 * @param pending          {@code PENDING} plus {@code IN_FLIGHT} rows ({@code outbox_pending_events})
 * @param quarantined      {@code QUARANTINED} rows ({@code outbox_quarantined_events})
 * @param oldestPendingAge age of the oldest {@code PENDING} or {@code IN_FLIGHT} row, null when there is none
 *                         ({@code outbox_oldest_pending_age_seconds})
 */
public record OutboxStats(long pending, long quarantined, Duration oldestPendingAge) {

    public static final OutboxStats EMPTY = new OutboxStats(0, 0, null);
}
