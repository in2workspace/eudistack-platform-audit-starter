package com.eudistack.auditoria.context;

/**
 * An audit context opened on the current thread. Close it in the same thread, in reverse order of opening, with
 * try-with-resources; the previous context comes back.
 */
public interface AuditContextScope extends AutoCloseable {

    /**
     * Restores the previous context. Closing twice does nothing.
     *
     * @throws IllegalStateException if a scope opened later on this thread is still open
     */
    @Override
    void close();
}
