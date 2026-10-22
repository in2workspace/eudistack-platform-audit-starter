package com.eudistack.auditoria.jdbc;

import com.eudistack.auditoria.sql.OutboxSql;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates {@code public.audit_outbox} once, at startup, over JDBC (DT-002 §3.7.3). It runs on its own
 * connection, never in the per-tenant migration loop of the producer (R-1), and the script names
 * {@code public} explicitly, so the {@code search_path} of the connection does not matter.
 *
 * <p>When the table and its index already exist it does nothing at all: no lock and no DDL, so a database user
 * without {@code CREATE} on {@code public} starts fine once the table is provisioned. Otherwise it takes an
 * advisory lock, so instances starting together do not collide, and runs the idempotent script.
 */
@Slf4j
public final class JdbcAuditOutboxSchema implements InitializingBean {

    private final DataSource dataSource;

    public JdbcAuditOutboxSchema(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (isPresent(connection)) {
                log.debug("public.audit_outbox already exists");
                return;
            }
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement lock = connection.createStatement()) {
                lock.execute(OutboxSql.SCHEMA_LOCK);
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(OutboxSql.SCHEMA_SCRIPT));
                connection.commit();
                log.info("public.audit_outbox is ready");
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private static boolean isPresent(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(OutboxSql.SCHEMA_PRESENT)) {
            return result.next() && result.getBoolean(1);
        }
    }
}
