package com.eudistack.auditoria.r2dbc;

import com.eudistack.auditoria.domain.OutboxEntry;
import com.eudistack.auditoria.port.OutboxStore;
import com.eudistack.auditoria.support.BlockingOutboxStore;
import com.eudistack.auditoria.support.OutboxStoreContract;
import com.eudistack.auditoria.support.PostgresTestDatabase;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * DT-002 §2.3 {@code R2dbcOutboxStoreIT}: the outbox contract on R2DBC, with the business transaction of a reactive
 * producer: the business {@code Mono} and the insert composed in one {@code TransactionalOperator}.
 */
class R2dbcOutboxStoreIT extends OutboxStoreContract {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ConnectionFactory connectionFactory = PostgresTestDatabase.connectionFactory();
    private final R2dbcOutboxStore reactiveStore = new R2dbcOutboxStore(connectionFactory);
    private final DatabaseClient client = DatabaseClient.create(connectionFactory);
    private final TransactionalOperator businessTransaction =
            TransactionalOperator.create(new R2dbcTransactionManager(connectionFactory));

    @Override
    protected OutboxStore store() {
        return new BlockingOutboxStore(reactiveStore);
    }

    @Override
    protected void inBusinessTransaction(UUID businessId, OutboxEntry entry, Runnable beforeCommit) {
        Mono<Void> business = client.sql("SELECT set_config('search_path', 'sandbox', true)").fetch().first()
                .then(client.sql("INSERT INTO issuance (id) VALUES (:id)").bind("id", businessId).fetch().rowsUpdated())
                .then(reactiveStore.insert(entry))
                .then(Mono.fromRunnable(beforeCommit));
        businessTransaction.transactional(business).block(TIMEOUT);
    }

    @Test
    void invalidArguments_areErrorSignals_notExceptionsWhenBuildingTheMono() {
        assertThatCode(() -> reactiveStore.claim(0, "pod-a")).doesNotThrowAnyException();

        StepVerifier.create(reactiveStore.claim(0, "pod-a")).expectError(IllegalArgumentException.class).verify(TIMEOUT);
        StepVerifier.create(reactiveStore.releaseOwned(" ")).expectError(IllegalArgumentException.class).verify(TIMEOUT);
        StepVerifier.create(reactiveStore.reclaimExpired(Duration.ofSeconds(-1)))
                .expectError(IllegalArgumentException.class).verify(TIMEOUT);
    }
}
