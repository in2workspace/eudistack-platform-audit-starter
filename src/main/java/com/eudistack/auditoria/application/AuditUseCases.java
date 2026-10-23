package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditFact;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The facts a running {@link AuditUseCase} has prepared, per thread (DT-002 AD-6). Use cases nest: a fact goes to
 * the innermost one. Reactive use cases use {@link ReactiveAuditUseCases}.
 */
public final class AuditUseCases {

    static final String NO_USE_CASE = "prepare needs a running use case: a method annotated with @AuditUseCase or "
            + "an action of AuditUseCaseOperator";

    private static final ThreadLocal<Deque<List<AuditFact>>> RUNNING = new ThreadLocal<>();

    private AuditUseCases() {
    }

    /**
     * Adds {@code fact} to the running use case; it is staged with the result of the use case when it ends.
     *
     * @throws IllegalStateException when no use case is running on this thread
     */
    public static void prepare(AuditFact fact) {
        Objects.requireNonNull(fact, "fact");
        Deque<List<AuditFact>> running = RUNNING.get();
        if (running == null || running.isEmpty()) {
            throw new IllegalStateException(NO_USE_CASE);
        }
        running.peek().add(fact);
    }

    /** Starts a use case with {@code initial} facts; close it on the same thread. */
    static Running start(List<AuditFact> initial) {
        Deque<List<AuditFact>> running = RUNNING.get();
        if (running == null) {
            running = new ArrayDeque<>();
            RUNNING.set(running);
        }
        List<AuditFact> facts = new ArrayList<>(initial);
        running.push(facts);
        return new Running(facts);
    }

    record Running(List<AuditFact> facts) implements AutoCloseable {

        @Override
        public void close() {
            Deque<List<AuditFact>> running = RUNNING.get();
            if (running != null) {
                // By identity: two use cases can hold equal lists (both empty, say).
                running.removeIf(candidate -> candidate == facts);
                if (running.isEmpty()) {
                    RUNNING.remove();
                }
            }
        }
    }
}
