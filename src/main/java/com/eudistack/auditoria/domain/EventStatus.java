package com.eudistack.auditoria.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * What the Audit API did with one event of a batch (OpenAPI {@code EventBatchResult.results[].status}). The three
 * remove the row from the outbox (AD-2).
 */
public enum EventStatus {

    ACCEPTED("accepted"),
    DUPLICATE("duplicate"),
    REJECTED("rejected");

    private final String wireValue;

    EventStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static Optional<EventStatus> fromWire(String value) {
        return Arrays.stream(values()).filter(status -> status.wireValue.equals(value)).findFirst();
    }
}
