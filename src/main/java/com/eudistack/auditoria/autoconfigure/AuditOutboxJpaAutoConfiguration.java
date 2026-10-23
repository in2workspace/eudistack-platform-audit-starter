package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.jdbc.JdbcAuditOutboxSchema;
import com.eudistack.auditoria.jpa.JpaOutboxStore;
import com.eudistack.auditoria.port.OutboxStore;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.DependsOn;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Outbox on JPA for servlet producers on Spring Data JPA (DT-002 §3.4.1, AD-3). Evaluated before the JDBC
 * auto-configuration: a JPA application always has a {@link DataSource} too, and its store has to be the JPA one.
 * The table is created over JDBC, by the same initializer.
 */
@AutoConfiguration(
        after = {HibernateJpaAutoConfiguration.class, AuditOutboxAutoConfiguration.class},
        before = AuditOutboxJdbcAutoConfiguration.class)
@ConditionalOnClass({EntityManagerFactory.class, SharedEntityManagerCreator.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnBean({EntityManagerFactory.class, DataSource.class, PlatformTransactionManager.class})
@Conditional(AuditoriaEnabledCondition.class)
public class AuditOutboxJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    JdbcAuditOutboxSchema auditOutboxSchema(DataSource dataSource) {
        return new JdbcAuditOutboxSchema(dataSource);
    }

    @Bean
    @DependsOn("auditOutboxSchema")
    @ConditionalOnMissingBean(OutboxStore.class)
    JpaOutboxStore auditOutboxStore(EntityManagerFactory entityManagerFactory,
                                    PlatformTransactionManager transactionManager) {
        return new JpaOutboxStore(SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
                transactionManager);
    }
}
