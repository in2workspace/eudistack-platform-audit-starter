package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.context.AuditContextWebFilter;
import com.eudistack.auditoria.context.ReactorAuditContextAccessor;
import com.eudistack.auditoria.context.ThreadLocalAuditContextAccessor;
import com.eudistack.auditoria.port.AuditContextAccessor;
import com.eudistack.auditoria.port.AuditContextResolver;
import com.eudistack.auditoria.port.ReactiveAuditContextAccessor;
import com.eudistack.auditoria.port.ReactiveAuditContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §3.4.1 and D-S6: a reactive application reads the Reactor Context and any other one the thread; the request
 * filter exists only when the producer declares its resolver; nothing exists with the switches off (AD-8).
 */
class AuditContextAutoConfigurationTest {

    private static final AutoConfigurations STARTER =
            AutoConfigurations.of(AuditOutboxAutoConfiguration.class, AuditContextAutoConfiguration.class);

    private static final String[] PUBLISHER_ON = {"auditoria.publisher.enabled=true", "auditoria.source=issuer"};

    private final AuditContextResolver servletResolver = (request, authentication) -> Optional.empty();
    private final ReactiveAuditContextResolver reactiveResolver = (exchange, authentication) -> Mono.empty();

    @Test
    void servletProducer_withItsResolver_getsTheThreadAccessorAndTheRequestFilter() {
        new WebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(PUBLISHER_ON)
                .withBean(AuditContextResolver.class, () -> servletResolver)
                .run(context -> {
                    assertThat(context.getBean(AuditContextAccessor.class))
                            .isInstanceOf(ThreadLocalAuditContextAccessor.class);
                    assertThat(context).hasSingleBean(FilterRegistrationBean.class);
                    assertThat(context).doesNotHaveBean(ReactiveAuditContextAccessor.class);
                });
    }

    @Test
    void servletProducer_withoutResolver_onlyGetsTheThreadAccessor() {
        new WebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(PUBLISHER_ON)
                .run(context -> {
                    assertThat(context).hasSingleBean(AuditContextAccessor.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }

    @Test
    void reactiveProducer_withItsResolver_getsTheReactorAccessorAndTheWebFilter() {
        new ReactiveWebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(PUBLISHER_ON)
                .withBean(ReactiveAuditContextResolver.class, () -> reactiveResolver)
                .run(context -> {
                    assertThat(context.getBean(ReactiveAuditContextAccessor.class))
                            .isInstanceOf(ReactorAuditContextAccessor.class);
                    assertThat(context).hasSingleBean(AuditContextWebFilter.class);
                    assertThat(context).doesNotHaveBean(AuditContextAccessor.class);
                });
    }

    @Test
    void nonWebProcess_getsTheThreadAccessorForItsExplicitScopes() {
        new ApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(PUBLISHER_ON)
                .run(context -> assertThat(context.getBean(AuditContextAccessor.class))
                        .isInstanceOf(ThreadLocalAuditContextAccessor.class));
    }

    @Test
    void switchesOff_registerNothing() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .withBean(AuditContextResolver.class, () -> servletResolver)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AuditContextAccessor.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                });
    }
}
