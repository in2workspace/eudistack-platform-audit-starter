package com.eudistack.auditoria.r2dbc;

import com.eudistack.auditoria.sql.OutboxSql;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Creates {@code public.audit_outbox} once, at startup, over R2DBC: the same behaviour as the JDBC initializer
 * (docs/decisions.md, D-S18) for reactive producers, which may have no {@code DataSource} at all. It skips
 * everything when the table and its index exist; otherwise it takes the advisory lock and runs the script in one
 * transaction on its own connection.
 *
 * <p>It waits for the result at startup, before the application serves anything: never on a request path.
 */
@Slf4j
public final class R2dbcAuditOutboxSchema implements InitializingBean {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(60);

    private final ConnectionFactory connectionFactory;

    public R2dbcAuditOutboxSchema(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public void afterPropertiesSet() {
        initialize().block(STARTUP_TIMEOUT);
    }

    Mono<Void> initialize() {
        return Mono.usingWhen(
                Mono.from(connectionFactory.create()),
                connection -> isPresent(connection).flatMap(present -> {
                    if (present) {
                        log.debug("public.audit_outbox already exists");
                        return Mono.empty();
                    }
                    return create(connection);
                }),
                connection -> Mono.from(connection.close()));
    }

    private static Mono<Boolean> isPresent(Connection connection) {
        return Flux.from(connection.createStatement(OutboxSql.SCHEMA_PRESENT).execute())
                .flatMap(result -> result.map((row, metadata) -> Boolean.TRUE.equals(row.get(0, Boolean.class))))
                .next()
                .defaultIfEmpty(false);
    }

    private static Mono<Void> create(Connection connection) {
        Mono<Void> lock = Flux.from(connection.createStatement(OutboxSql.SCHEMA_LOCK).execute())
                .flatMap(result -> result.map((row, metadata) -> true))
                .then();
        Mono<Void> script = new ResourceDatabasePopulator(new ClassPathResource(OutboxSql.SCHEMA_SCRIPT))
                .populate(connection);
        return Mono.from(connection.beginTransaction())
                .then(lock)
                .then(script)
                .then(Mono.from(connection.commitTransaction()))
                .doOnSuccess(ignored -> log.info("public.audit_outbox is ready"))
                .onErrorResume(error -> Mono.from(connection.rollbackTransaction()).then(Mono.error(error)));
    }
}
