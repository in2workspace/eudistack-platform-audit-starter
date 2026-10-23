package com.eudistack.auditoria.port;

/**
 * Wakes the dispatcher up after a business transaction that staged events has committed (DT-002 §3.4.3, RQ-13),
 * so delivery does not wait for the next poll. Called once per committed transaction, never on rollback.
 * A lost signal is harmless: the poll picks the rows up (EC-03).
 */
@FunctionalInterface
public interface OutboxWakeup {

    /** Must return at once and never fail: it runs inside the commit of the caller. */
    void signal();
}
