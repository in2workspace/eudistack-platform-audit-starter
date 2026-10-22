package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DT-002 §3.7.3 and R-1: the initializer creates exactly one {@code public.audit_outbox}, whatever the
 * {@code search_path} of its connection and however many instances start at once, and does nothing once it
 * exists.
 */
class JdbcAuditOutboxSchemaIT {

    private final DataSource dataSource = PostgresTestDatabase.dataSource();
    private final JdbcClient jdbc = JdbcClient.create(dataSource);

    @BeforeEach
    void dropTheOutbox() {
        jdbc.sql("DROP TABLE IF EXISTS public.audit_outbox").update();
    }

    @Test
    void createsTheTableAndItsIndexInPublic() throws Exception {
        new JdbcAuditOutboxSchema(dataSource).afterPropertiesSet();

        assertThat(jdbc.sql("SELECT to_regclass('public.audit_outbox') IS NOT NULL "
                + "AND to_regclass('public.audit_outbox_pending_idx') IS NOT NULL").query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void secondRun_changesNothing() throws Exception {
        JdbcAuditOutboxSchema schema = new JdbcAuditOutboxSchema(dataSource);
        schema.afterPropertiesSet();
        jdbc.sql("INSERT INTO public.audit_outbox (id, event_id, tenant, event_type, correlation_id, payload) "
                + "VALUES (gen_random_uuid(), gen_random_uuid(), 'sandbox', 'IssuanceCompleted', 'op-1', '{}')")
                .update();

        assertThatCode(schema::afterPropertiesSet).doesNotThrowAnyException();
        assertThat(jdbc.sql("SELECT count(*) FROM public.audit_outbox").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void connectionOnATenantSchema_stillCreatesOnlyTheTableInPublic() throws Exception {
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS sandbox").update();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS kpmg").update();

        try (HikariDataSource tenantConnections = PostgresTestDatabase.dataSourceOnSchema("sandbox")) {
            new JdbcAuditOutboxSchema(tenantConnections).afterPropertiesSet();
        }

        assertThat(jdbc.sql("SELECT table_schema FROM information_schema.tables WHERE table_name = 'audit_outbox'")
                .query(String.class).list()).containsExactly("public");
    }

    @Test
    void instancesStartingTogether_createOneTableWithoutErrors() throws Exception {
        int instances = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Void>> startups = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            startups.add(() -> {
                start.await();
                new JdbcAuditOutboxSchema(dataSource).afterPropertiesSet();
                return null;
            });
        }

        try (ExecutorService executor = Executors.newFixedThreadPool(instances)) {
            List<Future<Void>> results = startups.stream().map(executor::submit).toList();
            start.countDown();
            for (Future<Void> result : results) {
                assertThatCode(() -> result.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_outbox'")
                .query(Long.class).single()).isEqualTo(1);
    }
}
