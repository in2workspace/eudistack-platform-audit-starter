package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.support.OutboxStoreContract;

import java.util.UUID;

/**
 * DT-002 §2.3 {@code JdbcOutboxStoreIT}: the outbox contract on JDBC, with the business transaction of a servlet
 * producer ({@code @Transactional} / {@code TransactionTemplate} on the same {@code DataSource}).
 */
class JdbcOutboxStoreIT extends OutboxStoreContract {

    private final JdbcOutboxStore store = new JdbcOutboxStore(dataSource);

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Override
    protected void inBusinessTransaction(UUID businessId, OutboxEntry entry, Runnable beforeCommit) {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("SELECT set_config('search_path', 'sandbox', true)").query(String.class).single();
            jdbc.sql("INSERT INTO issuance (id) VALUES (:id)").param("id", businessId).update();
            store.insert(entry);
            beforeCommit.run();
        });
    }
}
