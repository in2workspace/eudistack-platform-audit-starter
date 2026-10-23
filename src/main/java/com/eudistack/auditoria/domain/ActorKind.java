package com.eudistack.auditoria.domain;

/**
 * {@code actor.kind} of an audit event (OpenAPI {@code Actor}).
 */
public enum ActorKind {

    PERSON("Person"),
    SYSTEM("System"),
    HOLDER("Holder");

    private final String wireValue;

    ActorKind(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Value sent to the Audit API. */
    public String wireValue() {
        return wireValue;
    }
}
