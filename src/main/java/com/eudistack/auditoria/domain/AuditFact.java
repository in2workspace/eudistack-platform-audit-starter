package com.eudistack.auditoria.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What happened, as the use case knows it: the catalogue type, its action, the resource and the
 * {@code details} the type declares (AD-6). It carries no result and no context: the result comes with
 * {@link AuditDraft}, and tenant, actor, organization and {@code correlation_id} come from the
 * {@link AuditContext} when the draft is staged.
 *
 * <p>{@code source} overrides {@code auditoria.source} for a module of the same process ({@code status-lists},
 * {@code signing}). {@code tenant}, when set, must match the tenant of the context (EC-08); leave it null
 * otherwise. Null {@code details} values are omitted.
 */
public record AuditFact(
        String eventType,
        String action,
        String resource,
        Map<String, Object> details,
        String source,
        String tenant
) {

    public AuditFact {
        Require.text(eventType, "event_type");
        Require.text(action, "action");
        Require.text(resource, "resource");
        details = AuditDetails.normalize(details);
        Require.optionalText(source, "source");
        Require.optionalText(tenant, "tenant");
    }

    public static Builder builder(String eventType) {
        return new Builder(eventType);
    }

    public static final class Builder {

        private final String eventType;
        private final Map<String, Object> details = new LinkedHashMap<>();
        private String action;
        private String resource;
        private String source;
        private String tenant;

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder resource(String resource) {
            this.resource = resource;
            return this;
        }

        /** Adds one {@code details} field. A null value is accepted and omitted. */
        public Builder detail(String key, Object value) {
            details.put(key, value);
            return this;
        }

        /** Adds every entry of {@code values} to {@code details}. Null values are accepted and omitted. */
        public Builder details(Map<String, ?> values) {
            details.putAll(values);
            return this;
        }

        public Builder source(String source) {
            this.source = source;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public AuditFact build() {
            return new AuditFact(eventType, action, resource, details, source, tenant);
        }
    }
}
