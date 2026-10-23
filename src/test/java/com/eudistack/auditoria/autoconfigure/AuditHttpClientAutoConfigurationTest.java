package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.http.RestClientAuditHttpClient;
import com.eudistack.auditoria.http.WebClientAuditHttpClient;
import com.eudistack.auditoria.port.AuditAccessTokenProvider;
import com.eudistack.auditoria.port.AuditHttpClient;
import com.eudistack.auditoria.port.ReactiveAuditAccessTokenProvider;
import com.eudistack.auditoria.port.ReactiveAuditHttpClient;
import com.eudistack.auditoria.support.FakeAccessTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DT-002 §3.3 and D-S9: the client of the Audit API exists only with the dispatcher on, uses the client of the
 * producer's runtime, and needs the producer's M2M token provider.
 */
class AuditHttpClientAutoConfigurationTest {

    private static final AutoConfigurations STARTER =
            AutoConfigurations.of(AuditOutboxAutoConfiguration.class, AuditHttpClientAutoConfiguration.class);

    private static final String[] DISPATCHER_ON = {
            "auditoria.dispatcher.enabled=true", "auditoria.client.base-url=https://audit.internal.stg.eudistack.net"};

    private final FakeAccessTokens tokens = new FakeAccessTokens();

    @Test
    void servletProducer_withItsTokenProvider_getsTheRestClient() {
        new WebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(DISPATCHER_ON)
                .withBean(AuditAccessTokenProvider.class, tokens::blocking)
                .run(context -> assertThat(context.getBean(AuditHttpClient.class))
                        .isInstanceOf(RestClientAuditHttpClient.class));
    }

    @Test
    void reactiveProducer_withItsTokenProvider_getsTheWebClient() {
        new ReactiveWebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(DISPATCHER_ON)
                .withBean(ReactiveAuditAccessTokenProvider.class, tokens::reactive)
                .run(context -> {
                    assertThat(context.getBean(ReactiveAuditHttpClient.class))
                            .isInstanceOf(WebClientAuditHttpClient.class);
                    assertThat(context).doesNotHaveBean(AuditHttpClient.class);
                });
    }

    @Test
    void dispatcherOn_withoutATokenProvider_failsAtStartupSayingWhatIsMissing() {
        new WebApplicationContextRunner().withConfiguration(STARTER).withPropertyValues(DISPATCHER_ON)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("AuditAccessTokenProvider").hasMessageContaining("Audit · Write");
                });
    }

    @Test
    void publisherOnly_hasNoClient_theBusinessPathNeverCallsTheApi() {
        new WebApplicationContextRunner().withConfiguration(STARTER)
                .withPropertyValues("auditoria.publisher.enabled=true", "auditoria.source=verifier")
                .withBean(AuditAccessTokenProvider.class, tokens::blocking)
                .run(context -> assertThat(context).doesNotHaveBean(AuditHttpClient.class));
    }
}
