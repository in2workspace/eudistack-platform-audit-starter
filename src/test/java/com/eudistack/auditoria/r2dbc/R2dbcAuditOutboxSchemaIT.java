package com.eudistack.auditoria.r2dbc;

import com.eudistack.auditoria.support.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import reactor.core.publisher.Flux;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DT-002 §3.7.3 and R-1 for reactive producers, which may have no {@code DataSource}: the R2DBC initializer creates
 * one {@code public.audit_outbox}, also when several instances start together, and does nothing once it exists.
 */
class R2dbcAuditOutboxSchemaIT {

    private final JdbcClient jdbc = JdbcClient.create(PostgresTestDatabase.dataSource());

    @BeforeEach
    void dropTheOutbox() {
        jdbc.sql("DROP TABLE IF EXISTS public.audit_outbox").update();
    }

    @Test
    void createsTheTableAndItsIndexInPublic_andASecondRunChangesNothing() {
        R2dbcAuditOutboxSchema schema = new R2dbcAuditOutboxSchema(PostgresTestDatabase.connectionFactory());

        schema.afterPropertiesSet();
        assertThatCode(schema::afterPropertiesSet).doesNotThrowAnyException();

        assertThat(jdbc.sql("SELECT to_regclass('public.audit_outbox') IS NOT NULL "
                + "AND to_regclass('public.audit_outbox_pending_idx') IS NOT NULL").query(Boolean.class).single())
                .isTrue();
    }

    @Test
    void instancesStartingTogether_createOneTableWithoutErrors() {
        Flux.range(0, 8)
                .flatMap(instance -> new R2dbcAuditOutboxSchema(PostgresTestDatabase.connectionFactory()).initialize())
                .then()
                .block(Duration.ofSeconds(30));

        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'audit_outbox'")
                .query(Long.class).single()).isEqualTo(1);
    }
}
