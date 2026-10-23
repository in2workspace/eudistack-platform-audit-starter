package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.http.RestClientAuditHttpClient;
import com.eudistack.auditoria.http.WebClientAuditHttpClient;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.eudistack.auditoria.port.AuditHttpClient;
import com.eudistack.auditoria.port.ReactiveAuditAccessTokenProvider;
import com.eudistack.auditoria.port.ReactiveAuditHttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The client of {@code POST /api/v1/events} (DT-002 §3.3, RQ-26), only with the dispatcher on: the business path
 * never talks to the Audit API. It needs the producer's M2M token provider (docs/decisions.md, D-S9); without it,
 * startup fails saying so.
 */
@AutoConfiguration(after = AuditOutboxAutoConfiguration.class)
@ConditionalOnBooleanProperty("auditoria.dispatcher.enabled")
public class AuditHttpClientAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @Conditional(NotReactiveApplicationCondition.class)
    @ConditionalOnClass(RestClient.class)
    static class Blocking {

        @Bean
        @ConditionalOnMissingBean
        AuditHttpClient auditHttpClient(AuditoriaProperties properties,
                                        ObjectProvider<AuditAccessTokenProvider> tokens) {
            AuditoriaProperties.Client client = properties.getClient();
            return new RestClientAuditHttpClient(client.getBaseUrl(), client.getPath(), client.getAudience(),
                    required(tokens.getIfAvailable(), AuditAccessTokenProvider.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(WebClient.class)
    static class Reactive {

        @Bean
        @ConditionalOnMissingBean
        ReactiveAuditHttpClient auditHttpClient(AuditoriaProperties properties,
                                                ObjectProvider<ReactiveAuditAccessTokenProvider> tokens) {
            AuditoriaProperties.Client client = properties.getClient();
            return new WebClientAuditHttpClient(client.getBaseUrl(), client.getPath(), client.getAudience(),
                    required(tokens.getIfAvailable(), ReactiveAuditAccessTokenProvider.class));
        }
    }

    private static <T> T required(T provider, Class<T> type) {
        if (provider == null) {
            throw new IllegalStateException("auditoria.dispatcher.enabled=true needs a " + type.getSimpleName()
                    + " bean: the M2M access token of this service for the Audit API, with the power "
                    + "organization · EUDISTACK · Audit · Write.");
        }
        return provider;
    }
}
