package com.eudistack.auditoria.context;

import com.eudistack.auditoria.domain.AuditContext;

import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The stack of audit contexts of each thread, for servlet producers and background processes. Each frame holds a
 * lazy, memoized context: the request frame calls the producer's resolver only when a {@code stage} first asks,
 * and every later read in the request gets the same context, generated {@code correlation_id} included.
 */
final class ThreadAuditContexts {

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private ThreadAuditContexts() {
    }

    static Optional<AuditContext> current() {
        Frame frame = CURRENT.get();
        return frame == null ? Optional.empty() : frame.context().get();
    }

    /** Opens a frame whose context is computed, once, the first time it is read. */
    static AuditContextScope open(Supplier<Optional<AuditContext>> context) {
        Frame frame = new Frame(new Memoized(context), CURRENT.get());
        CURRENT.set(frame);
        return frame;
    }

    /** Opens a frame derived from the current one, still lazily. */
    static AuditContextScope derive(UnaryOperator<AuditContext> change) {
        Frame previous = CURRENT.get();
        return open(() -> previous == null ? Optional.empty() : previous.context().get().map(change));
    }

    private static final class Frame implements AuditContextScope {

        private final Memoized context;
        private final Frame previous;
        private boolean closed;

        private Frame(Memoized context, Frame previous) {
            this.context = context;
            this.previous = previous;
        }

        Memoized context() {
            return context;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (CURRENT.get() != this) {
                throw new IllegalStateException("Audit context scopes must be closed in reverse order of opening");
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static final class Memoized implements Supplier<Optional<AuditContext>> {

        private final Supplier<Optional<AuditContext>> source;
        private Optional<AuditContext> value;

        private Memoized(Supplier<Optional<AuditContext>> source) {
            this.source = source;
        }

        @Override
        public synchronized Optional<AuditContext> get() {
            if (value == null) {
                value = source.get().map(CorrelationIds::ensure);
            }
            return value;
        }
    }
}
