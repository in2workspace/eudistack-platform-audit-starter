package com.eudistack.auditoria.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;

/**
 * One PostgreSQL 17 container for the whole test run, as the producers run it. Started on first use and
 * stopped by Testcontainers when the JVM ends.
 */
public final class PostgresTestDatabase {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HikariDataSource dataSource;

    private PostgresTestDatabase() {
    }

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            dataSource = newDataSource(POSTGRES.getJdbcUrl());
        }
        return dataSource;
    }

    /**
     * A separate pool whose connections start with {@code search_path} on {@code schema}, as a tenant connection
     * of a producer does. The caller closes it.
     */
    public static HikariDataSource dataSourceOnSchema(String schema) {
        dataSource();
        String url = POSTGRES.getJdbcUrl();
        return newDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
    }

    private static HikariDataSource newDataSource(String url) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(12);
        return new HikariDataSource(config);
    }
}
