package com.eudistack.auditoria.domain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the {@code details} a producer builds into an immutable JSON-shaped tree: maps with string keys,
 * lists, strings, numbers and booleans. A null value is omitted (DT-002 §3.3 step 6: a field the step did not
 * know is left out). Instants become UTC ISO-8601 strings, dates ISO dates and UUIDs their text, so the
 * serialized form does not depend on any ObjectMapper setting.
 *
 * <p>Error messages name the path and the type, never the value: a rejected value may be personal data.
 */
final class AuditDetails {

    private AuditDetails() {
    }

    static Map<String, Object> normalize(Map<String, ?> details) {
        return details == null ? Map.of() : normalizeMap(details, "details");
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> map, String path) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException(path + " has a key that is not a non-blank string");
            }
            Object value = normalizeValue(entry.getValue(), path + "." + key);
            if (value != null) {
                copy.put(key, value);
            }
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<Object> normalizeList(Collection<?> collection, String path) {
        List<Object> copy = new ArrayList<>(collection.size());
        int index = 0;
        for (Object element : collection) {
            Object value = normalizeValue(element, path + "[" + index++ + "]");
            if (value != null) {
                copy.add(value);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static Object normalizeValue(Object value, String path) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Double number when !Double.isFinite(number) -> throw notFinite(path);
            case Float number when !Float.isFinite(number) -> throw notFinite(path);
            case Integer number -> number;
            case Long number -> number;
            case Short number -> number;
            case Byte number -> number;
            case Double number -> number;
            case Float number -> number;
            case BigInteger number -> number;
            case BigDecimal number -> number;
            case UUID uuid -> uuid.toString();
            case Instant instant -> instant.toString();
            case OffsetDateTime dateTime -> dateTime.toInstant().toString();
            case ZonedDateTime dateTime -> dateTime.toInstant().toString();
            case LocalDate date -> date.toString();
            case Map<?, ?> nested -> normalizeMap(nested, path);
            case Collection<?> collection -> normalizeList(collection, path);
            default -> throw new IllegalArgumentException(path + " has an unsupported type "
                    + value.getClass().getName() + ": use a string, number, boolean, date, UUID, map or list. "
                    + "Enum values of the catalogue are strings.");
        };
    }

    private static IllegalArgumentException notFinite(String path) {
        return new IllegalArgumentException(path + " is not a finite number");
    }
}
