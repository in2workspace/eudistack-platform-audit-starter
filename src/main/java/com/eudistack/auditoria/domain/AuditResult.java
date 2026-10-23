package com.eudistack.auditoria.domain;

/**
 * {@code result} of an audit event (OpenAPI {@code Result}).
 */
public enum AuditResult {

    SUCCESS("Success"),
    DENIED("Denied"),
    FAILURE("Failure");

    private final String wireValue;

    AuditResult(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Value sent to the Audit API. */
    public String wireValue() {
        return wireValue;
    }
}
