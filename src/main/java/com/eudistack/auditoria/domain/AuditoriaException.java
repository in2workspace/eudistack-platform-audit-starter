package com.eudistack.auditoria.domain;

/**
 * Root of the errors of the starter that make {@code stage} fail and the business transaction roll back.
 */
public abstract sealed class AuditoriaException extends RuntimeException
        permits AuditTenantMismatchException, AuditDraftRejectedException {

    protected AuditoriaException(String message) {
        super(message);
    }
}
