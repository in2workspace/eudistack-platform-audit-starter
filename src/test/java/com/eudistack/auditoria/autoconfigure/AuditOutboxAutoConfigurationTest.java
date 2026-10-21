package com.eudistack.auditoria.autoconfigure;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §3.8 and AD-8: the starter is inert until a switch is on, and then binds the documented defaults and
 * rejects a configuration that cannot work.
 */
class AuditOutboxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AuditOutboxAutoConfiguration.class));

    @Test
    void autoConfiguration_isRegisteredForSpringBoot() {
        Iterable<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());

        assertThat(candidates).contains(AuditOutboxAutoConfiguration.class.getName());
    }

    @Nested
    class SwitchesOff {

        @Test
        void byDefault_registersNothing() {
            runner.run(context -> assertThat(starterBeans(context)).isEmpty());
        }

        @Test
        void explicitlyFalse_registersNothingAndIgnoresTheRestOfTheConfiguration() {
            runner.withPropertyValues(
                            "auditoria.publisher.enabled=false",
                            "auditoria.dispatcher.enabled=false",
                            "auditoria.dispatcher.batch-size=0")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(starterBeans(context)).isEmpty();
                    });
        }
    }

    @Nested
    class Defaults {

        @Test
        void publisherOn_bindsTheDefaultsOfTheDesign() {
            runner.withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=issuer")
                    .run(context -> {
                        AuditoriaProperties properties = context.getBean(AuditoriaProperties.class);

                        assertThat(properties.getSource()).isEqualTo("issuer");
                        assertThat(properties.getCatalogVersion()).isEqualTo("2.2.0");
                        assertThat(properties.getPublisher().isEnabled()).isTrue();
                        assertThat(properties.getDispatcher().isEnabled()).isFalse();
                        assertThat(properties.getDispatcher().getBatchSize()).isEqualTo(100);
                        assertThat(properties.getDispatcher().getPollInterval()).isEqualTo(Duration.ofSeconds(5));
                        assertThat(properties.getDispatcher().getLease()).isEqualTo(Duration.ofSeconds(30));
                        assertThat(properties.getDispatcher().getInstanceId()).isNull();
                        assertThat(properties.getClient().getPath()).isEqualTo("/api/v1/events");
                        assertThat(properties.getClient().getBaseUrl()).isNull();
                        assertThat(properties.getClient().getAudience()).isNull();
                    });
        }

        @Test
        void dispatcherOnAlone_isEnoughToActivateTheStarter() {
            runner.withPropertyValues(
                            "auditoria.dispatcher.enabled=true",
                            "auditoria.client.base-url=https://audit.internal.stg.eudistack.net")
                    .run(context -> {
                        AuditoriaProperties properties = context.getBean(AuditoriaProperties.class);

                        assertThat(properties.getPublisher().isEnabled()).isFalse();
                        assertThat(properties.getClient().getBaseUrl())
                                .isEqualTo(URI.create("https://audit.internal.stg.eudistack.net"));
                    });
        }

        @Test
        void batchSizeAboveTheContract_isCappedAt100() {
            runner.withPropertyValues(
                            "auditoria.publisher.enabled=true",
                            "auditoria.source=issuer",
                            "auditoria.dispatcher.batch-size=500")
                    .run(context -> assertThat(context.getBean(AuditoriaProperties.class)
                            .getDispatcher().getBatchSize()).isEqualTo(100));
        }
    }

    @Nested
    class InvalidConfiguration {

        @Test
        void publisherWithoutSource_failsAtStartup() {
            assertStartupFails("auditoria.source is required",
                    "auditoria.publisher.enabled=true");
        }

        @Test
        void dispatcherWithoutBaseUrl_failsAtStartup() {
            assertStartupFails("auditoria.client.base-url is required",
                    "auditoria.dispatcher.enabled=true");
        }

        @Test
        void baseUrlWithPath_failsAtStartup() {
            assertStartupFails("must be an origin without path",
                    "auditoria.dispatcher.enabled=true",
                    "auditoria.client.base-url=https://audit.internal.stg.eudistack.net/api/v1/events");
        }

        @Test
        void relativeBaseUrl_failsAtStartup() {
            assertStartupFails("must be an absolute http(s) URL",
                    "auditoria.dispatcher.enabled=true",
                    "auditoria.client.base-url=audit.internal.stg.eudistack.net");
        }

        @Test
        void batchSizeBelowOne_failsAtStartup() {
            assertStartupFails("batch-size must be at least 1",
                    "auditoria.publisher.enabled=true", "auditoria.source=issuer",
                    "auditoria.dispatcher.batch-size=0");
        }

        @Test
        void leaseNotLongerThanTheReadTimeout_failsAtStartup() {
            assertStartupFails("lease must be longer than the HTTP read timeout",
                    "auditoria.publisher.enabled=true", "auditoria.source=issuer",
                    "auditoria.dispatcher.lease=2s");
        }

        @Test
        void malformedCatalogVersion_failsAtStartup() {
            assertStartupFails("catalog-version must be MAJOR.MINOR.PATCH",
                    "auditoria.publisher.enabled=true", "auditoria.source=issuer",
                    "auditoria.catalog-version=2.2");
        }

        private void assertStartupFails(String message, String... properties) {
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining(message);
            });
        }
    }

    private static String[] starterBeans(AssertableApplicationContext context) {
        return Arrays.stream(context.getBeanDefinitionNames())
                .filter(name -> {
                    Class<?> type = context.getType(name);
                    return type != null && type.getName().startsWith("com.eudistack.auditoria");
                })
                .toArray(String[]::new);
    }
}
