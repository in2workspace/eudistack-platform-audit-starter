package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.jdbc.JdbcOutboxStore;
import com.eudistack.auditoria.jpa.JpaOutboxStore;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.port.ReactiveOutboxStore;
import com.eudistack.auditoria.r2dbc.R2dbcOutboxStore;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §2.3 (EC-14) and the selection table of §3.4.1: which store each kind of producer gets, with every
 * auto-configuration of the starter present.
 */
class AuditOutboxStoreSelectionIT {

    private static final AutoConfigurations STARTER = AutoConfigurations.of(
            AuditOutboxAutoConfiguration.class,
            AuditOutboxR2dbcAutoConfiguration.class,
            AuditOutboxJpaAutoConfiguration.class,
            AuditOutboxJdbcAutoConfiguration.class);

    private static final String[] PUBLISHER_ON = {"auditoria.publisher.enabled=true", "auditoria.source=issuer"};

    // Not closeable: the contexts must not close the shared test pool.
    private final DataSource dataSource = new DelegatingDataSource(PostgresTestDatabase.dataSource());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);

    @BeforeEach
    void dropTheOutbox() {
        jdbc.sql("DROP TABLE IF EXISTS public.audit_outbox").update();
    }

    @Test
    void reactiveProducer_withR2dbcAndAJdbcDriver_getsTheR2dbcStoreOnly() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(STARTER)
                .withBean(ConnectionFactory.class, PostgresTestDatabase::connectionFactory)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(PUBLISHER_ON)
                .run(context -> {
                    assertThat(context).hasSingleBean(ReactiveOutboxStore.class);
                    assertThat(context.getBean(ReactiveOutboxStore.class)).isInstanceOf(R2dbcOutboxStore.class);
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                    assertThat(tableExists()).isTrue();
                });
    }

    @Test
    void servletProducer_withJdbcOnly_getsTheJdbcStore() {
        new WebApplicationContextRunner()
                .withConfiguration(STARTER)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(PUBLISHER_ON)
                .run(context -> {
                    assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
                    assertThat(context).doesNotHaveBean(ReactiveOutboxStore.class);
                    assertThat(tableExists()).isTrue();
                });
    }

    @Test
    void servletProducer_withJpa_getsTheJpaStore() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withConfiguration(STARTER)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(PUBLISHER_ON)
                .withPropertyValues("spring.jpa.hibernate.ddl-auto=none")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxStore.class);
                    assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JpaOutboxStore.class);
                    assertThat(tableExists()).isTrue();
                });
    }

    @Test
    void reactiveProducer_withTheSwitchesOff_getsNothing() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(STARTER)
                .withBean(ConnectionFactory.class, PostgresTestDatabase::connectionFactory)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ReactiveOutboxStore.class);
                    assertThat(tableExists()).isFalse();
                });
    }

    private boolean tableExists() {
        return jdbc.sql("SELECT to_regclass('public.audit_outbox') IS NOT NULL").query(Boolean.class).single();
    }
}
