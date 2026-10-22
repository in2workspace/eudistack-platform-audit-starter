package com.eudistack.auditoria.sql;

import com.eudistack.auditoria.domain.OutboxLimits;

import java.time.Duration;

/**
 * Checks and conversions of the arguments of the outbox operations, shared by the three stores so they reject
 * the same input in the same way.
 */
public final class OutboxArguments {

    private OutboxArguments() {
    }

    /** {@code limit} of a claim: 1 to {@link OutboxLimits#MAX_BATCH_SIZE}. */
    public static int limit(int limit) {
        if (limit < 1 || limit > OutboxLimits.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + OutboxLimits.MAX_BATCH_SIZE);
        }
        return limit;
    }

    public static String instance(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId is required");
        }
        return instanceId;
    }

    /** {@code duration} in seconds, for {@code make_interval(secs => ...)}. */
    public static double seconds(Duration duration) {
        if (duration == null || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be zero or positive");
        }
        return duration.toMillis() / 1000.0;
    }
}
