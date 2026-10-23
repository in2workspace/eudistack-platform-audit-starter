package com.eudistack.auditoria.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Conditional;

/**
 * Entry point of the audit outbox starter (DT-002 §3.2). Nothing is registered unless
 * {@code auditoria.publisher.enabled} or {@code auditoria.dispatcher.enabled} is true (AD-8).
 */
@AutoConfiguration
@Conditional(AuditoriaEnabledCondition.class)
@EnableConfigurationProperties(AuditoriaProperties.class)
public class AuditOutboxAutoConfiguration {
}
