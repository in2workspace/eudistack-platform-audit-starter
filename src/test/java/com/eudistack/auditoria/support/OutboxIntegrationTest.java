package com.eudistack.auditoria.support;

import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.jdbc.JdbcAuditOutboxSchema;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Base of the integration tests on {@code public.audit_outbox}: the table exists and is empty before each test,
 * and {@code sandbox.issuance} plays the business table of a tenant schema.
 */
public abstract class OutboxIntegrationTest {

    protected final DataSource dataSource = PostgresTestDatabase.dataSource();
    protected final JdbcClient jdbc = JdbcClient.create(dataSource);
    protected final TransactionTemplate transaction =
            new TransactionTemplate(new DataSourceTransactionManager(dataSource));

    @BeforeEach
    void cleanOutbox() throws Exception {
        new JdbcAuditOutboxSchema(dataSource).afterPropertiesSet();
        jdbc.sql("CREATE SCHEMA IF NOT EXISTS sandbox").update();
        jdbc.sql("CREATE TABLE IF NOT EXISTS sandbox.issuance (id UUID PRIMARY KEY)").update();
        jdbc.sql("TRUNCATE public.audit_outbox, sandbox.issuance").update();
    }

    protected static OutboxEntry entry(UUID eventId) {
        return new OutboxEntry(eventId, "sandbox", "IssuanceCompleted", "op-" + eventId,
                "{\"event_id\": \"" + eventId + "\", \"tenant\": \"sandbox\"}", null);
    }

    /** Inserts {@code count} pending rows one statement after another, so their {@code created_at} grows. */
    protected List<UUID> insertPending(int count, Consumer<OutboxEntry> insert) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            insert.accept(entry(id));
            ids.add(id);
        }
        return ids;
    }

    protected Map<String, Object> row(UUID eventId) {
        return jdbc.sql("SELECT * FROM public.audit_outbox WHERE event_id = :id").param("id", eventId)
                .query().singleRow();
    }

    protected long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
