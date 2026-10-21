package com.eudistack.auditoria.domain;

/**
 * The draft names a tenant other than the one of the audit context (EC-08): an event of tenant A would be
 * written during an operation of tenant B.
 */
public final class AuditTenantMismatchException extends AuditoriaException {

    public AuditTenantMismatchException(String draftTenant, String contextTenant) {
        super("The draft tenant '" + draftTenant + "' is not the tenant of the audit context '" + contextTenant
                + "' (EC-08)");
    }
}
