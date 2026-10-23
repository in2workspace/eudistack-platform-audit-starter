package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditContext;
import com.eudistack.auditoria.domain.AuditDraft;
import com.eudistack.auditoria.domain.AuditEvent;
import com.eudistack.auditoria.domain.OutboxEntry;

import java.time.Clock;
import java.util.UUID;

/**
 * Turns a draft into the outbox row, the same for the servlet and the reactive publisher (DT-002 §3.3 steps 2 to 7):
 * context and draft make the event, with an {@code event_id} and an {@code occurred_at} generated once (EC-08 checked
 * on the way); the guard rejects secrets; the event is serialized as the API's {@code AuditEventInput}. No I/O and no
 * transaction here: the publishers do that.
 */
public final class AuditStaging {

    private final String catalogVersion;
    private final String defaultSource;
    private final Clock clock;
    private final AuditDraftGuard guard = new AuditDraftGuard();
    private final AuditEventJson json = new AuditEventJson();

    /**
     * @param catalogVersion {@code auditoria.catalog-version}, stamped as {@code event_version}
     * @param defaultSource  {@code auditoria.source}, used when the draft does not name its own
     */
    public AuditStaging(String catalogVersion, String defaultSource, Clock clock) {
        this.catalogVersion = catalogVersion;
        this.defaultSource = defaultSource;
        this.clock = clock;
    }

    /**
     * @throws com.eudistack.auditoria.domain.AuditTenantMismatchException when the draft names another tenant (EC-08)
     * @throws com.eudistack.auditoria.domain.AuditDraftRejectedException when the event would carry a secret (RQ-10)
     */
    public OutboxEntry prepare(AuditDraft draft, AuditContext context) {
        AuditEvent event = AuditEvent.of(draft, context, UUID.randomUUID(), clock.instant(), catalogVersion,
                defaultSource);
        guard.check(event);
        return OutboxEntry.of(event, json.write(event), null);
    }
}
