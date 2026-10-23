package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.jdbc.JdbcAuditOutboxSchema;
import com.eudistack.auditoria.jdbc.JdbcOutboxStore;
import com.eudistack.auditoria.port.OutboxStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;

/**
 * Outbox on JDBC for servlet producers (DT-002 §3.4.1, AD-3). Only in a servlet application: a reactive producer
 * that also has a JDBC driver, like the Issuer with Flyway, never ends up here (EC-14).
 */
@AutoConfiguration(after = {DataSourceAutoConfiguration.class, AuditOutboxAutoConfiguration.class})
@ConditionalOnClass(JdbcClient.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean(DataSource.class)
@Conditional(AuditoriaEnabledCondition.class)
public class AuditOutboxJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JdbcAuditOutboxSchema auditOutboxSchema(DataSource dataSource) {
        return new JdbcAuditOutboxSchema(dataSource);
    }

    @Bean
    @DependsOn("auditOutboxSchema")
    @ConditionalOnMissingBean(OutboxStore.class)
    JdbcOutboxStore auditOutboxStore(DataSource dataSource) {
        return new JdbcOutboxStore(dataSource);
    }
}
