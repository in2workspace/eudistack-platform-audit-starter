package com.eudistack.auditoria.domain;

import java.util.UUID;

/**
 * The result of one event in a {@code 200} of the Audit API.
 *
 * @param errorType the {@code type} of the error of a {@code rejected} event ({@code unknown_event_type},
 *                  {@code invalid_event}, {@code unknown_tenant}, {@code event_id_conflict}); null otherwise
 */
public record EventResult(UUID eventId, EventStatus status, String errorType) {

    public EventResult {
        Require.present(eventId, "event_id");
        Require.present(status, "status");
    }
}
