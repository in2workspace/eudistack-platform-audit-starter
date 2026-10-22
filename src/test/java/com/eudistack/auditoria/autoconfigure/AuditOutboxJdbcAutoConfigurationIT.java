package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.jdbc.JdbcOutboxStore;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DT-002 §3.4.1, selection table: a servlet producer with a {@link DataSource} gets {@link JdbcOutboxStore} and the
 * table, unless it already defines an {@link OutboxStore}; with both switches off, or outside a servlet
 * application, nothing is created (AD-8, EC-14).
 */
class AuditOutboxJdbcAutoConfigurationIT {

    private static final AutoConfigurations STARTER =
            AutoConfigurations.of(AuditOutboxAutoConfiguration.class, AuditOutboxJdbcAutoConfiguration.class);

    // Not closeable: the context must not close the shared test pool.
    private final DataSource dataSource = new DelegatingDataSource(PostgresTestDatabase.dataSource());
    private final JdbcClient jdbc = JdbcClient.create(dataSource);

    private final WebApplicationContextRunner servletProducer = new WebApplicationContextRunner()
            .withConfiguration(STARTER)
            .withBean(DataSource.class, () -> dataSource);

    @BeforeEach
    void dropTheOutbox() {
        jdbc.sql("DROP TABLE IF EXISTS public.audit_outbox").update();
    }

    @Test
    void servletProducer_withThePublisherOn_getsTheJdbcStoreAndTheTable() {
        servletProducer.withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxStore.class);
                    assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
                    assertThat(tableExists()).isTrue();
                });
    }

    @Test
    void switchesOff_createNeitherStoreNorTable() {
        servletProducer.run(context -> {
            assertThat(context).doesNotHaveBean(OutboxStore.class);
            assertThat(tableExists()).isFalse();
        });
    }

    @Test
    void producerDefinedStore_isKept() {
        OutboxStore own = mock(OutboxStore.class);

        servletProducer.withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier")
                .withBean("ownOutboxStore", OutboxStore.class, () -> own)
                .run(context -> assertThat(context.getBean(OutboxStore.class)).isSameAs(own));
    }

    @Test
    void nonServletApplication_doesNotGetTheJdbcStore() {
        new ApplicationContextRunner()
                .withConfiguration(STARTER)
                .withBean(DataSource.class, () -> dataSource)
                .withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=issuer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                });
    }

    private boolean tableExists() {
        return jdbc.sql("SELECT to_regclass('public.audit_outbox') IS NOT NULL").query(Boolean.class).single();
    }
}
