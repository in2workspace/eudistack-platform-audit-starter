package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.application.AuditStaging;
import com.eudistack.auditoria.application.DisabledAuditPublisher;
import com.eudistack.auditoria.application.DisabledReactiveAuditPublisher;
import com.eudistack.auditoria.application.OutboxAuditPublisher;
import com.eudistack.auditoria.application.ReactiveAuditPublisher;
import com.eudistack.auditoria.application.ReactiveOutboxAuditPublisher;
import com.eudistack.auditoria.port.AuditContextAccessor;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.port.OutboxWakeup;
import com.eudistack.auditoria.port.ReactiveAuditContextAccessor;
import com.eudistack.auditoria.port.ReactiveOutboxStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * The {@code AuditPublisher} / {@code ReactiveAuditPublisher} the producer injects (DT-002 §3.3, AD-8).
 *
 * <p>There is always one, so the producer's use cases start whatever the switches say: with
 * {@code auditoria.publisher.enabled=false} it does nothing (docs/decisions.md, D-S28). With it on, it stages in the
 * store of the producer's runtime, and startup fails if there is none.
 */
@AutoConfiguration(after = {
        AuditOutboxAutoConfiguration.class,
        AuditContextAutoConfiguration.class,
        AuditOutboxR2dbcAutoConfiguration.class,
        AuditOutboxJpaAutoConfiguration.class,
        AuditOutboxJdbcAutoConfiguration.class})
public class AuditPublisherAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty("auditoria.publisher.enabled")
    static class PublisherOn {

        /** Until the dispatcher runs in this process, a commit has nobody to wake up. */
        @Bean
        @ConditionalOnMissingBean
        OutboxWakeup outboxWakeup() {
            return () -> {
            };
        }

        @Bean
        @ConditionalOnMissingBean
        AuditStaging auditStaging(AuditoriaProperties properties, ObjectProvider<Clock> clock) {
            return new AuditStaging(properties.getCatalogVersion(), properties.getSource(),
                    clock.getIfAvailable(Clock::systemUTC));
        }

        @Configuration(proxyBeanMethods = false)
        @Conditional(NotReactiveApplicationCondition.class)
        static class Blocking {

            @Bean
            @ConditionalOnMissingBean
            AuditPublisher auditPublisher(AuditStaging staging, ObjectProvider<OutboxStore> store,
                                          AuditContextAccessor contexts, OutboxWakeup wakeup) {
                return new OutboxAuditPublisher(staging, required(store.getIfAvailable(),
                        "a servlet application with a DataSource (JDBC) or an EntityManagerFactory (JPA)"),
                        contexts, wakeup);
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        static class Reactive {

            @Bean
            @ConditionalOnMissingBean
            ReactiveAuditPublisher auditPublisher(AuditStaging staging, ObjectProvider<ReactiveOutboxStore> store,
                                                  ReactiveAuditContextAccessor contexts, OutboxWakeup wakeup) {
                return new ReactiveOutboxAuditPublisher(staging, required(store.getIfAvailable(),
                        "a reactive application with an R2DBC ConnectionFactory"), contexts, wakeup);
            }
        }

        private static <T> T required(T store, String needs) {
            if (store == null) {
                throw new IllegalStateException("auditoria.publisher.enabled=true needs an outbox store, and there is "
                        + "none: the starter creates it in " + needs + ".");
            }
            return store;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "auditoria.publisher.enabled", havingValue = false, matchIfMissing = true)
    static class PublisherOff {

        @Configuration(proxyBeanMethods = false)
        @Conditional(NotReactiveApplicationCondition.class)
        static class Blocking {

            @Bean
            @ConditionalOnMissingBean
            AuditPublisher auditPublisher() {
                return new DisabledAuditPublisher();
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
        @ConditionalOnClass(Mono.class)
        static class Reactive {

            @Bean
            @ConditionalOnMissingBean
            ReactiveAuditPublisher auditPublisher() {
                return new DisabledReactiveAuditPublisher();
            }
        }
    }
}
