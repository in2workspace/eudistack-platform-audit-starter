package com.eudistack.auditoria.autoconfigure;

import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;

/**
 * Matches when {@code auditoria.publisher.enabled} or {@code auditoria.dispatcher.enabled} is true (AD-8). Every
 * auto-configuration of the starter carries it, so the starter registers nothing while both are off.
 */
class AuditoriaEnabledCondition extends AnyNestedCondition {

    AuditoriaEnabledCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnBooleanProperty("auditoria.publisher.enabled")
    static class PublisherEnabled {
    }

    @ConditionalOnBooleanProperty("auditoria.dispatcher.enabled")
    static class DispatcherEnabled {
    }
}
