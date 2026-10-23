package com.eudistack.auditoria.support;

import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.eudistack.auditoria.port.ReactiveAuditAccessTokenProvider;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * M2M tokens of a test producer: {@code token-1} until a refresh, then {@code token-2}. Can be made to fail.
 */
public final class FakeAccessTokens {

    private final AtomicInteger refreshes = new AtomicInteger();
    private volatile boolean failing;

    public void fail() {
        failing = true;
    }

    public int refreshes() {
        return refreshes.get();
    }

    private String current() {
        if (failing) {
            throw new IllegalStateException("verifier unavailable");
        }
        return refreshes.get() == 0 ? "token-1" : "token-2";
    }

    private String refreshed() {
        refreshes.incrementAndGet();
        return current();
    }

    public AuditAccessTokenProvider blocking() {
        return new AuditAccessTokenProvider() {
            @Override
            public String accessToken(String audience) {
                return current();
            }

            @Override
            public String refreshAccessToken(String audience) {
                return refreshed();
            }
        };
    }

    public ReactiveAuditAccessTokenProvider reactive() {
        return new ReactiveAuditAccessTokenProvider() {
            @Override
            public Mono<String> accessToken(String audience) {
                return Mono.fromCallable(FakeAccessTokens.this::current);
            }

            @Override
            public Mono<String> refreshAccessToken(String audience) {
                return Mono.fromCallable(FakeAccessTokens.this::refreshed);
            }
        };
    }
}
