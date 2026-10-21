package com.eudistack.auditoria.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;

/**
 * Entry point of the audit outbox starter (DT-002 §3.2). Nothing is registered unless
 * {@code auditoria.publisher.enabled} or {@code auditoria.dispatcher.enabled} is true (AD-8).
 */
@AutoConfiguration
@Conditional(AuditOutboxAutoConfiguration.OnAnySwitchEnabled.class)
@EnableConfigurationProperties(AuditoriaProperties.class)
public class AuditOutboxAutoConfiguration {

    static class OnAnySwitchEnabled extends AnyNestedCondition {

        OnAnySwitchEnabled() {
            super(ConfigurationPhase.PARSE_CONFIGURATION);
        }

        @ConditionalOnBooleanProperty("auditoria.publisher.enabled")
        static class PublisherEnabled {
        }

        @ConditionalOnBooleanProperty("auditoria.dispatcher.enabled")
        static class DispatcherEnabled {
        }
    }
}
