package com.eudistack.auditoria.jpa;

import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.support.OutboxStoreContract;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TransactionRequiredException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §2.3 {@code JpaOutboxStoreIT}: the outbox contract on JPA native queries, with the business transaction of a
 * JPA producer ({@code JpaTransactionManager}). No entity is mapped (R-4).
 */
class JpaOutboxStoreIT extends OutboxStoreContract {

    private static EntityManagerFactory entityManagerFactory;
    private static JpaTransactionManager transactionManager;

    private final EntityManager entityManager =
            SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    private final JpaOutboxStore store = new JpaOutboxStore(entityManager, transactionManager);
    private final TransactionTemplate businessTransaction = new TransactionTemplate(transactionManager);

    @BeforeAll
    static void startJpa() {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(PostgresTestDatabase.dataSource());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        // A package without entities: the persistence unit maps nothing.
        factory.setPackagesToScan("com.eudistack.auditoria.jpa.noentities");
        factory.setPersistenceUnitName("audit-outbox-test");
        factory.afterPropertiesSet();
        entityManagerFactory = factory.getObject();
        transactionManager = new JpaTransactionManager(entityManagerFactory);
    }

    @AfterAll
    static void stopJpa() {
        entityManagerFactory.close();
    }

    @Override
    protected OutboxStore store() {
        return store;
    }

    @Override
    protected void inBusinessTransaction(UUID businessId, OutboxEntry entry, Runnable beforeCommit) {
        businessTransaction.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SELECT set_config('search_path', 'sandbox', true)").getSingleResult();
            entityManager.createNativeQuery("INSERT INTO issuance (id) VALUES (:id)").setParameter("id", businessId)
                    .executeUpdate();
            store.insert(entry);
            beforeCommit.run();
        });
    }

    @Test
    void insert_withoutABusinessTransaction_isRefused() {
        assertThatThrownBy(() -> store.insert(entry(UUID.randomUUID())))
                .isInstanceOf(TransactionRequiredException.class);

        assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
    }

    @Test
    void persistenceUnit_mapsNoEntity() {
        assertThat(entityManagerFactory.getMetamodel().getEntities()).isEmpty();
    }
}
