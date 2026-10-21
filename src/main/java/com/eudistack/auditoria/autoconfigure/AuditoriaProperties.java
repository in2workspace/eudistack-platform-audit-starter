package com.eudistack.auditoria.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Configuration of the audit outbox ({@code auditoria.*}, DT-002 §3.8). Both switches start off (AD-8): adding
 * the dependency changes nothing until a service turns them on, and only then is this bean created and
 * validated.
 */
@Slf4j
@Getter
@Setter
@ConfigurationProperties(prefix = "auditoria")
public class AuditoriaProperties implements InitializingBean {

    /** Largest batch of the Audit API contract ({@code EventBatch.maxItems}). Configuration cannot raise it. */
    public static final int MAX_BATCH_SIZE = 100;

    /** Read timeout of the dispatcher's HTTP client (DT-002 §2.4). The lease has to outlast it. */
    public static final Duration HTTP_READ_TIMEOUT = Duration.ofSeconds(2);

    private static final Pattern CATALOG_VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    /**
     * Default {@code source} of the events of this process ({@code issuer}, {@code verifier}, {@code wallet-ebw}).
     * A draft may override it ({@code status-lists}, {@code signing}). Required when the publisher is enabled.
     */
    private String source;

    /** Catalogue version stamped as {@code event_version} on every staged event. */
    private String catalogVersion = "2.2.0";

    private final Publisher publisher = new Publisher();
    private final Dispatcher dispatcher = new Dispatcher();
    private final Client client = new Client();

    @Getter
    @Setter
    public static class Publisher {
        /** Inserts into {@code public.audit_outbox} only when true. */
        private boolean enabled;
    }

    @Getter
    @Setter
    public static class Dispatcher {
        /** Starts the drain loop that sends the outbox to the Audit API only when true. */
        private boolean enabled;
        /** Rows claimed and sent per batch. Capped at {@value AuditoriaProperties#MAX_BATCH_SIZE}. */
        private int batchSize = MAX_BATCH_SIZE;
        /** Wait between drains while the outbox is empty, unless a post-commit signal arrives first. */
        private Duration pollInterval = Duration.ofSeconds(5);
        /** Age after which an {@code IN_FLIGHT} row of a dead instance goes back to {@code PENDING}. */
        private Duration lease = Duration.ofSeconds(30);
        /** {@code locked_by} of the rows this instance claims. Empty: host name plus start time. */
        private String instanceId;
        /**
         * Most rows quarantined within {@link #quarantineWindow} before the dispatcher assumes contract drift,
         * stops quarantining and alerts (AD-9). Provisional value until PA-01 is settled.
         */
        private int quarantineMaxPerWindow = 10;
        /** Window of {@link #quarantineMaxPerWindow}. Provisional value until PA-01 is settled. */
        private Duration quarantineWindow = Duration.ofMinutes(10);

        public int getBatchSize() {
            return Math.min(batchSize, MAX_BATCH_SIZE);
        }
    }

    @Getter
    @Setter
    public static class Client {
        /** Origin of the internal write host, without path, e.g. {@code https://audit.internal.stg.eudistack.net}. */
        private URI baseUrl;
        /** Path of {@code recordEvents}. */
        private String path = "/api/v1/events";
        /**
         * Audience requested for the M2M token of the dispatcher. Empty is tolerated while the Audit API does not
         * enforce one (PA-03).
         */
        private String audience;
    }

    @Override
    public void afterPropertiesSet() {
        require(CATALOG_VERSION.matcher(String.valueOf(catalogVersion)).matches(),
                "auditoria.catalog-version must be MAJOR.MINOR.PATCH, was '" + catalogVersion + "'");
        require(dispatcher.batchSize >= 1, "auditoria.dispatcher.batch-size must be at least 1");
        require(isPositive(dispatcher.pollInterval), "auditoria.dispatcher.poll-interval must be positive");
        require(dispatcher.lease != null && dispatcher.lease.compareTo(HTTP_READ_TIMEOUT) > 0,
                "auditoria.dispatcher.lease must be longer than the HTTP read timeout (" + HTTP_READ_TIMEOUT + ")");
        require(dispatcher.quarantineMaxPerWindow >= 1,
                "auditoria.dispatcher.quarantine-max-per-window must be at least 1");
        require(isPositive(dispatcher.quarantineWindow), "auditoria.dispatcher.quarantine-window must be positive");
        require(client.path != null && client.path.startsWith("/"), "auditoria.client.path must start with '/'");

        if (publisher.enabled) {
            require(source != null && !source.isBlank(),
                    "auditoria.source is required when auditoria.publisher.enabled=true");
        }
        if (dispatcher.enabled) {
            validateBaseUrl();
            if (client.audience == null || client.audience.isBlank()) {
                log.warn("auditoria.client.audience is empty: the dispatcher will request a token without audience "
                        + "(PA-03). Set it as soon as the Audit API enforces one.");
            }
        }
    }

    private void validateBaseUrl() {
        URI baseUrl = client.baseUrl;
        require(baseUrl != null, "auditoria.client.base-url is required when auditoria.dispatcher.enabled=true");
        require(baseUrl.isAbsolute() && ("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme())),
                "auditoria.client.base-url must be an absolute http(s) URL, was '" + baseUrl + "'");
        String path = baseUrl.getPath();
        require(path == null || path.isEmpty() || "/".equals(path),
                "auditoria.client.base-url must be an origin without path (the path is auditoria.client.path), was '"
                        + baseUrl + "'");
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && duration.isPositive();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
