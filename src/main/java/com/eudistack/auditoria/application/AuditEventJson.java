package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes an {@link AuditEvent} as the OpenAPI {@code AuditEventInput}: the JSON stored in
 * {@code public.audit_outbox.payload} and sent to the Audit API as is (DT-002 §3.3 step 4).
 *
 * <p>Field by field, with its own {@link ObjectMapper}: the contract does not depend on the naming strategy,
 * inclusion or date settings of the producer. Absent optional fields are omitted, never sent as null, and
 * there is no {@code recorded_at}.
 */
public final class AuditEventJson {

    private final ObjectMapper mapper = new ObjectMapper();

    public String write(AuditEvent event) {
        ObjectNode node = mapper.createObjectNode();
        node.put("event_id", event.eventId().toString());
        node.put("event_type", event.eventType());
        node.put("event_version", event.eventVersion());
        node.put("occurred_at", event.occurredAt().toString());
        node.put("tenant", event.tenant());
        putIfPresent(node, "organization", event.organization());
        node.put("source", event.source());
        node.set("actor", actor(event.actor()));
        node.put("action", event.action());
        node.put("resource", event.resource());
        node.put("result", event.result().wireValue());
        putIfPresent(node, "reason", event.reason());
        node.put("correlation_id", event.correlationId());
        node.set("details", mapper.valueToTree(event.details()));
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // The details tree only holds JSON types (AuditFact normalizes it): this cannot happen.
            throw new IllegalStateException("Audit event " + event.eventId() + " could not be serialized", e);
        }
    }

    private ObjectNode actor(AuditActor actor) {
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", actor.kind().wireValue());
        node.put("id", actor.id());
        putIfPresent(node, "name", actor.name());
        putIfPresent(node, "email", actor.email());
        putIfPresent(node, "organization", actor.organization());
        putIfPresent(node, "attestation_id", actor.attestationId());
        return node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
