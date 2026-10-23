package com.eudistack.auditoria.http;

import java.time.Duration;

/**
 * Timeouts of the dispatcher's calls to the Audit API (DT-002 §2.4), fixed in code. The {@code IN_FLIGHT} lease has
 * to outlast {@link #READ}.
 */
public final class AuditApiTimeouts {

    public static final Duration CONNECT = Duration.ofSeconds(1);
    public static final Duration READ = Duration.ofSeconds(2);

    private AuditApiTimeouts() {
    }
}
