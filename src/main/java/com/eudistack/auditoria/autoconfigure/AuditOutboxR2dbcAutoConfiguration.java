package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.port.ReactiveOutboxStore;
import com.eudistack.auditoria.r2dbc.R2dbcAuditOutboxSchema;
import com.eudistack.auditoria.r2dbc.R2dbcOutboxStore;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.DependsOn;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Outbox on R2DBC for reactive producers (DT-002 §3.4.1, AD-3): Issuer and EBW. Only in a reactive application, so
 * the JDBC driver that the Issuer keeps for Flyway never decides the store (EC-14).
 */
@AutoConfiguration(after = {R2dbcAutoConfiguration.class, AuditOutboxAutoConfiguration.class})
@ConditionalOnClass({DatabaseClient.class, ConnectionFactory.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBean(ConnectionFactory.class)
@Conditional(AuditoriaEnabledCondition.class)
public class AuditOutboxR2dbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    R2dbcAuditOutboxSchema auditOutboxSchema(ConnectionFactory connectionFactory) {
        return new R2dbcAuditOutboxSchema(connectionFactory);
    }

    @Bean
    @DependsOn("auditOutboxSchema")
    @ConditionalOnMissingBean(ReactiveOutboxStore.class)
    R2dbcOutboxStore auditOutboxStore(ConnectionFactory connectionFactory) {
        return new R2dbcOutboxStore(connectionFactory);
    }
}
