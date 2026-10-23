package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.application.DisabledAuditPublisher;
import com.eudistack.auditoria.application.DisabledReactiveAuditPublisher;
import com.eudistack.auditoria.application.OutboxAuditPublisher;
import com.eudistack.auditoria.application.ReactiveAuditPublisher;
import com.eudistack.auditoria.application.ReactiveOutboxAuditPublisher;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 AD-8 and D-S28: the producer always gets a publisher to inject. Off, it does nothing and needs nothing;
 * on, it stages in the store of its runtime, and startup fails when there is no store to stage in.
 */
class AuditPublisherAutoConfigurationIT {

    private static final AutoConfigurations STARTER = AutoConfigurations.of(
            AuditOutboxAutoConfiguration.class,
            AuditContextAutoConfiguration.class,
            AuditOutboxR2dbcAutoConfiguration.class,
            AuditOutboxJpaAutoConfiguration.class,
            AuditOutboxJdbcAutoConfiguration.class,
            AuditPublisherAutoConfiguration.class);

    // Not closeable: the contexts must not close the shared test pool.
    private final DataSource dataSource = new DelegatingDataSource(PostgresTestDatabase.dataSource());

    @Test
    void servletProducer_withTheSwitchesOff_getsAPublisherThatDoesNothing() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .run(context -> assertThat(context.getBean(AuditPublisher.class))
                        .isInstanceOf(DisabledAuditPublisher.class));
    }

    @Test
    void reactiveProducer_withTheSwitchesOff_getsAPublisherThatDoesNothing() {
        new ReactiveWebApplicationContextRunner().withConfiguration(STARTER)
                .run(context -> assertThat(context.getBean(ReactiveAuditPublisher.class))
                        .isInstanceOf(DisabledReactiveAuditPublisher.class));
    }

    @Test
    void dispatcherOnlyProducer_stagesNothing() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("auditoria.dispatcher.enabled=true",
                        "auditoria.client.base-url=https://audit.internal.stg.eudistack.net")
                .run(context -> assertThat(context.getBean(AuditPublisher.class))
                        .isInstanceOf(DisabledAuditPublisher.class));
    }

    @Test
    void servletProducer_withThePublisherOn_stagesInTheOutbox() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier")
                .run(context -> assertThat(context.getBean(AuditPublisher.class))
                        .isInstanceOf(OutboxAuditPublisher.class));
    }

    @Test
    void reactiveProducer_withThePublisherOn_stagesInTheOutbox() {
        new ReactiveWebApplicationContextRunner().withConfiguration(STARTER)
                .withBean(ConnectionFactory.class, PostgresTestDatabase::connectionFactory)
                .withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=issuer")
                .run(context -> assertThat(context.getBean(ReactiveAuditPublisher.class))
                        .isInstanceOf(ReactiveOutboxAuditPublisher.class));
    }

    @Test
    void publisherOn_withoutADatabase_failsAtStartupSayingWhatIsMissing() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("needs an outbox store").hasMessageContaining("DataSource");
                });
    }
}
