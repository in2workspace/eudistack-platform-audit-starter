package com.eudistack.auditoria.application;

import com.eudistack.auditoria.domain.AuditActor;
import com.eudistack.auditoria.domain.AuditDraftRejectedException;
import com.eudistack.auditoria.domain.AuditEvent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rejects an event that would carry a secret (RQ-10, DT-002 §3.3 step 5): no event holds tokens, keys,
 * passwords or presented credentials (PRD EUD-244 §5).
 *
 * <p>Two checks, anywhere in {@code details} and in the actor, resource and action:
 * <ul>
 *     <li>a key in {@link #SECRET_KEYS}, compared exactly after normalizing case and separators
 *         ({@code accessToken}, {@code access-token} → {@code access_token}); exact on purpose, so catalogue
 *         fields such as {@code secret_id} or {@code credential_configuration_id} stay valid;</li>
 *     <li>a string value shaped like a compact JWS or JWE, whatever its key.</li>
 * </ul>
 * The exception names the path, never the value.
 */
public final class AuditDraftGuard {

    static final Set<String> SECRET_KEYS = Set.of(
            "token", "access_token", "refresh_token", "id_token", "vp_token",
            "dpop", "authorization", "cookie",
            "password", "secret", "client_secret", "client_assertion", "private_key",
            "wia", "pre_authorized_code", "tx_code");

    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]*){2,4}$");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");

    public void check(AuditEvent event) {
        checkValue(event, "action", event.action());
        checkValue(event, "resource", event.resource());
        checkActor(event, event.actor());
        checkMap(event, "details", event.details());
    }

    private void checkActor(AuditEvent event, AuditActor actor) {
        checkValue(event, "actor.id", actor.id());
        checkValue(event, "actor.name", actor.name());
        checkValue(event, "actor.email", actor.email());
        checkValue(event, "actor.organization", actor.organization());
        checkValue(event, "actor.attestation_id", actor.attestationId());
    }

    private void checkMap(AuditEvent event, String path, Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String child = path + "." + key;
            if (SECRET_KEYS.contains(normalize(key))) {
                throw new AuditDraftRejectedException(event.eventType(), child + " is a secret key");
            }
            checkNode(event, child, entry.getValue());
        }
    }

    private void checkNode(AuditEvent event, String path, Object value) {
        switch (value) {
            case Map<?, ?> map -> checkMap(event, path, map);
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    checkNode(event, path + "[" + i + "]", list.get(i));
                }
            }
            case String text -> checkValue(event, path, text);
            default -> {
                // Numbers and booleans cannot carry a secret.
            }
        }
    }

    private static void checkValue(AuditEvent event, String path, String value) {
        if (value != null && JWT.matcher(value).matches()) {
            throw new AuditDraftRejectedException(event.eventType(), path + " carries a JWT-shaped value");
        }
    }

    private static String normalize(String key) {
        return CAMEL_BOUNDARY.matcher(key).replaceAll("$1_$2").toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
