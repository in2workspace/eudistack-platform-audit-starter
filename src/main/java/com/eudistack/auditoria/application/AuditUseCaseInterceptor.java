package com.eudistack.auditoria.application;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * Applies {@link AuditUseCase} (DT-002 AD-6): a method returning {@code Mono} goes to the reactive auditor, any other
 * to the blocking one. It never reads nor serializes the arguments or the return value of the method: tokens and
 * proofs travel there.
 */
public final class AuditUseCaseInterceptor implements MethodInterceptor {

    private static final String MONO = "reactor.core.publisher.Mono";
    private static final String FLUX = "reactor.core.publisher.Flux";

    private final Supplier<BlockingUseCaseAuditor> blocking;
    private final Supplier<ReactiveUseCaseAuditor> reactive;

    /**
     * @param blocking the auditor of a servlet or non-web application, null in a reactive one
     * @param reactive the auditor of a reactive application, null otherwise
     */
    public AuditUseCaseInterceptor(Supplier<BlockingUseCaseAuditor> blocking,
                                   Supplier<ReactiveUseCaseAuditor> reactive) {
        this.blocking = blocking;
        this.reactive = reactive;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        String useCase = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        // By name: a servlet producer may not have Reactor at all.
        String returnType = method.getReturnType().getName();
        if (FLUX.equals(returnType)) {
            throw new IllegalStateException(useCase + ": @AuditUseCase supports Mono, not Flux");
        }
        if (MONO.equals(returnType)) {
            return ReactiveUseCase.audit(reactive.get(), useCase, invocation);
        }
        BlockingUseCaseAuditor auditor = blocking.get();
        if (auditor == null) {
            throw new IllegalStateException(useCase + ": @AuditUseCase on a method that does not return Mono needs "
                    + "a servlet or non-web application");
        }
        return auditor.audit(useCase, List.of(), invocation::proceed);
    }

    /** Kept apart so this interceptor loads without Reactor. */
    private static final class ReactiveUseCase {

        @SuppressWarnings("unchecked")
        static Object audit(ReactiveUseCaseAuditor auditor, String useCase, MethodInvocation invocation) {
            if (auditor == null) {
                throw new IllegalStateException(useCase + ": @AuditUseCase on a Mono method needs a reactive "
                        + "application");
            }
            Mono<Object> body = Mono.defer(() -> {
                try {
                    return (Mono<Object>) invocation.proceed();
                } catch (Throwable error) {
                    return Mono.error(error);
                }
            });
            return auditor.audit(useCase, List.of(), body);
        }
    }
}
