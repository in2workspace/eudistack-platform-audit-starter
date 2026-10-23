package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.context.AuditContextFilter;
import com.eudistack.auditoria.context.AuditContextWebFilter;
import com.eudistack.auditoria.context.ReactorAuditContextAccessor;
import com.eudistack.auditoria.context.ThreadLocalAuditContextAccessor;
import com.eudistack.auditoria.port.AuditContextAccessor;
import com.eudistack.auditoria.port.AuditContextResolver;
import com.eudistack.auditoria.port.ReactiveAuditContextAccessor;
import com.eudistack.auditoria.port.ReactiveAuditContextResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import reactor.core.publisher.Mono;

/**
 * Where {@code stage} reads tenant, organization, actor and {@code correlation_id} (DT-002 §3.4.1, RQ-15, AD-10).
 * A reactive application reads the Reactor Context; any other one, the current thread. The request filter is
 * registered only when the producer declares its resolver (docs/decisions.md, D-S6) and has Spring Security;
 * explicit scopes for background processes work in every case.
 */
@AutoConfiguration(after = AuditOutboxAutoConfiguration.class)
@Conditional(AuditoriaEnabledCondition.class)
public class AuditContextAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(Mono.class)
    static class ReactiveContext {

        @Bean
        @ConditionalOnMissingBean
        ReactiveAuditContextAccessor auditContextAccessor() {
            return new ReactorAuditContextAccessor();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(ReactiveSecurityContextHolder.class)
        @ConditionalOnBean(ReactiveAuditContextResolver.class)
        static class RequestContext {

            @Bean
            @ConditionalOnMissingBean
            AuditContextWebFilter auditContextWebFilter(ReactiveAuditContextResolver resolver) {
                return new AuditContextWebFilter(resolver);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(NotReactiveApplicationCondition.class)
    static class ThreadContext {

        @Bean
        @ConditionalOnMissingBean
        AuditContextAccessor auditContextAccessor() {
            return new ThreadLocalAuditContextAccessor();
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnClass({OncePerRequestFilter.class, SecurityContextHolder.class})
        @ConditionalOnBean(AuditContextResolver.class)
        static class RequestContext {

            @Bean
            @ConditionalOnMissingBean(name = "auditContextFilter")
            FilterRegistrationBean<AuditContextFilter> auditContextFilter(AuditContextResolver resolver) {
                FilterRegistrationBean<AuditContextFilter> registration =
                        new FilterRegistrationBean<>(new AuditContextFilter(resolver));
                registration.setOrder(Ordered.LOWEST_PRECEDENCE);
                return registration;
            }
        }
    }
}
