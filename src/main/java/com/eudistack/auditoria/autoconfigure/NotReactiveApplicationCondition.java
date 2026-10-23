package com.eudistack.auditoria.autoconfigure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.NoneNestedConditions;

/**
 * Matches a servlet application or one without web: everything that is not reactive works on the current thread.
 */
class NotReactiveApplicationCondition extends NoneNestedConditions {

    NotReactiveApplicationCondition() {
        super(ConfigurationPhase.PARSE_CONFIGURATION);
    }

    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {
    }
}
