package com.eudistack.auditoria.autoconfigure;

import com.eudistack.auditoria.application.AuditPublisher;
import com.eudistack.auditoria.application.AuditUseCase;
import com.eudistack.auditoria.application.AuditUseCaseInterceptor;
import com.eudistack.auditoria.application.AuditUseCaseOperator;
import com.eudistack.auditoria.application.BlockingUseCaseAuditor;
import com.eudistack.auditoria.application.ReactiveAuditPublisher;
import com.eudistack.auditoria.application.ReactiveAuditUseCaseOperator;
import com.eudistack.auditoria.application.ReactiveUseCaseAuditor;
import com.eudistack.auditoria.port.AuditOutcomeClassifier;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * {@link AuditUseCase} and the operators (DT-002 AD-6, docs/decisions.md D-S32). Present whatever the switches say,
 * like the publisher (D-S28): with the publisher off they only run the use case.
 *
 * <p>The advisor is an infrastructure advisor, so it applies with the auto-proxy creator Spring Boot always
 * registers, with or without AspectJ. It resolves the auditors on first use, so creating it pulls nothing early.
 */
@AutoConfiguration(after = AuditPublisherAutoConfiguration.class)
@ConditionalOnClass(MethodInterceptor.class)
public class AuditUseCaseAutoConfiguration {

    private static final AuditOutcomeClassifier NOTHING_CLASSIFIED = error -> Optional.empty();

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor auditUseCaseAdvisor(ObjectProvider<BlockingUseCaseAuditor> blocking,
                                       ObjectProvider<ReactiveUseCaseAuditor> reactive) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                AnnotationMatchingPointcut.forMethodAnnotation(AuditUseCase.class),
                new AuditUseCaseInterceptor(blocking::getIfAvailable, reactive::getIfAvailable));
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE);
        return advisor;
    }

    @Configuration(proxyBeanMethods = false)
    @Conditional(NotReactiveApplicationCondition.class)
    static class Blocking {

        @Bean
        @ConditionalOnMissingBean(AuditUseCaseOperator.class)
        BlockingUseCaseAuditor auditUseCaseOperator(AuditPublisher publisher,
                                                    ObjectProvider<AuditOutcomeClassifier> classifier) {
            return new BlockingUseCaseAuditor(publisher, classifier.getIfAvailable(() -> NOTHING_CLASSIFIED));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(Mono.class)
    static class Reactive {

        @Bean
        @ConditionalOnMissingBean(ReactiveAuditUseCaseOperator.class)
        ReactiveUseCaseAuditor reactiveAuditUseCaseOperator(ReactiveAuditPublisher publisher,
                                                            ObjectProvider<AuditOutcomeClassifier> classifier) {
            return new ReactiveUseCaseAuditor(publisher, classifier.getIfAvailable(() -> NOTHING_CLASSIFIED));
        }
    }
}
