package com.eudistack.auditoria.application;

import com.eudistack.auditoria.autoconfigure.AuditContextAutoConfiguration;
import com.eudistack.auditoria.autoconfigure.AuditOutboxAutoConfiguration;
import com.eudistack.auditoria.autoconfigure.AuditOutboxJdbcAutoConfiguration;
import com.eudistack.auditoria.autoconfigure.AuditPublisherAutoConfiguration;
import com.eudistack.auditoria.autoconfigure.AuditUseCaseAutoConfiguration;
import com.eudistack.auditoria.context.AuditContextScope;
import com.eudistack.auditoria.context.AuditContexts;
import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditFact;
import com.eudistack.auditoria.domain.AuditOutcome;
import com.eudistack.auditoria.port.AuditOutcomeClassifier;
import com.eudistack.auditoria.support.OutboxIntegrationTest;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 AD-6 with the D-S32 rules, on a servlet producer with real Spring proxies: {@code @AuditUseCase} on the same
 * method as {@code @Transactional} stages its facts with the result of the method, inside the transaction; a failure
 * is staged with its classified outcome and lives or dies with its transaction; a use case outside a transaction fails
 * before running (EC-09); unclassified failures send nothing (RQ-11).
 */
class AuditUseCaseIT extends OutboxIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebApplicationContextRunner producer = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AuditOutboxAutoConfiguration.class,
                    AuditContextAutoConfiguration.class,
                    AuditOutboxJdbcAutoConfiguration.class,
                    AuditPublisherAutoConfiguration.class,
                    AuditUseCaseAutoConfiguration.class))
            .withUserConfiguration(Producer.class);

    @Test
    void transactionalUseCase_stagesItsFactAsSuccess_withTheBusinessChange() {
        run(context -> {
            context.getBean(RevocationService.class).revoke(UUID.randomUUID());

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            assertThat(onlyPayload().get("result").asText()).isEqualTo("Success");
        });
    }

    @Test
    void failureThatRollsBack_losesItsEventWithTheBusiness() {
        run(context -> {
            RevocationService service = context.getBean(RevocationService.class);

            assertThatThrownBy(() -> service.revokeDeniedAndRolledBack(UUID.randomUUID()))
                    .isInstanceOf(PowerMissingException.class);

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        });
    }

    @Test
    void failureThatCommits_isStagedWithItsClassifiedOutcome() {
        run(context -> {
            RevocationService service = context.getBean(RevocationService.class);

            assertThatThrownBy(() -> service.revokeDeniedAndKept(UUID.randomUUID()))
                    .isInstanceOf(PowerMissingException.class);

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            JsonNode payload = onlyPayload();
            assertThat(payload.get("result").asText()).isEqualTo("Denied");
            assertThat(payload.get("reason").asText()).isEqualTo("PowerMissing");
        });
    }

    @Test
    void unclassifiedFailure_stagesNothing() {
        run(context -> {
            RevocationService service = context.getBean(RevocationService.class);

            assertThatThrownBy(() -> service.revokeUnclassified(UUID.randomUUID()))
                    .isInstanceOf(UnclassifiedException.class);

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        });
    }

    @Test
    void useCaseWithoutAPreparedFact_stagesNothing() {
        run(context -> {
            context.getBean(RevocationService.class).revokeWithoutFact(UUID.randomUUID());

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        });
    }

    @Test
    void useCaseOutsideATransaction_failsBeforeRunningAnything() {
        run(context -> {
            RevocationService service = context.getBean(RevocationService.class);

            assertThatThrownBy(() -> service.revokeWithoutTransaction(UUID.randomUUID()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("EC-09");

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isZero();
        });
    }

    @Test
    void operator_stagesItsFactsAndThoseItsActionPrepares() {
        run(context -> {
            AuditUseCaseOperator useCases = context.getBean(AuditUseCaseOperator.class);
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

            String result = transaction.execute(status -> useCases.run(List.of(revocation()), () -> {
                AuditUseCases.prepare(revocation());
                return "revoked";
            }));

            assertThat(result).isEqualTo("revoked");
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isEqualTo(2);
        });
    }

    @Test
    void prepareOutsideAUseCase_fails() {
        assertThatThrownBy(() -> AuditUseCases.prepare(revocation()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@AuditUseCase");
    }

    @Test
    void publisherOff_useCasesRunAsWithoutTheStarter() {
        producer.run(context -> {
            context.getBean(RevocationService.class).revokeWithoutTransaction(UUID.randomUUID());

            assertThat(count("SELECT count(*) FROM sandbox.issuance")).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM public.audit_outbox")).isZero();
        });
    }

    /** Runs {@code test} with the publisher on, inside the audit context of a request of tenant sandbox. */
    private void run(ContextConsumer<AssertableWebApplicationContext> test) {
        producer.withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier").run(context -> {
            assertThat(context).hasNotFailed();
            try (AuditContextScope request = AuditContexts.open(
                    new AuditContext("sandbox", null, "op-1", AuditActor.system("verifier")))) {
                test.accept(context);
            }
        });
    }

    private JsonNode onlyPayload() throws Exception {
        List<String> payloads = jdbc.sql("SELECT CAST(payload AS text) FROM public.audit_outbox")
                .query(String.class).list();
        assertThat(payloads).hasSize(1);
        return JSON.readTree(payloads.getFirst());
    }

    static AuditFact revocation() {
        return AuditFact.builder("Revocation").action("Revoke").resource("issuance:0b6f1d2e").build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class Producer {

        @Bean
        DataSource dataSource() {
            return new DelegatingDataSource(PostgresTestDatabase.dataSource());
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        RevocationService revocationService(DataSource dataSource) {
            return new RevocationService(JdbcClient.create(dataSource));
        }

        @Bean
        AuditOutcomeClassifier auditOutcomeClassifier() {
            return error -> error instanceof PowerMissingException
                    ? Optional.of(AuditOutcome.denied("PowerMissing"))
                    : Optional.empty();
        }
    }

    static class RevocationService {

        private final JdbcClient jdbc;

        RevocationService(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        @Transactional
        @AuditUseCase
        public void revoke(UUID id) {
            insertBusiness(id);
            AuditUseCases.prepare(revocation());
        }

        @Transactional
        @AuditUseCase
        public void revokeDeniedAndRolledBack(UUID id) {
            insertBusiness(id);
            AuditUseCases.prepare(revocation());
            throw new PowerMissingException();
        }

        @Transactional(noRollbackFor = PowerMissingException.class)
        @AuditUseCase
        public void revokeDeniedAndKept(UUID id) {
            insertBusiness(id);
            AuditUseCases.prepare(revocation());
            throw new PowerMissingException();
        }

        @Transactional(noRollbackFor = UnclassifiedException.class)
        @AuditUseCase
        public void revokeUnclassified(UUID id) {
            insertBusiness(id);
            AuditUseCases.prepare(revocation());
            throw new UnclassifiedException();
        }

        @Transactional
        @AuditUseCase
        public void revokeWithoutFact(UUID id) {
            insertBusiness(id);
        }

        @AuditUseCase
        public void revokeWithoutTransaction(UUID id) {
            insertBusiness(id);
            AuditUseCases.prepare(revocation());
        }

        private void insertBusiness(UUID id) {
            jdbc.sql("INSERT INTO sandbox.issuance (id) VALUES (:id)").param("id", id).update();
        }
    }

    static class PowerMissingException extends RuntimeException {
    }

    static class UnclassifiedException extends RuntimeException {
    }
}
