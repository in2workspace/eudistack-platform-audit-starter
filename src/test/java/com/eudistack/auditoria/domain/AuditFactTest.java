package com.eudistack.auditoria.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DT-002 §3.3: the fact carries type, action, resource and {@code details}; a null field is omitted (step 6)
 * and the tree is normalized to JSON types, without leaking a rejected value in the error.
 */
class AuditFactTest {

    @Test
    void builder_buildsTheFact() {
        AuditFact fact = AuditFact.builder("IssuanceCompleted")
                .action("Issue")
                .resource("issuance:0b6f1d2e")
                .detail("attestation_type", "employee.sd.1")
                .details(Map.of("delivery_method", "email"))
                .source("signing")
                .tenant("sandbox")
                .build();

        assertThat(fact.eventType()).isEqualTo("IssuanceCompleted");
        assertThat(fact.action()).isEqualTo("Issue");
        assertThat(fact.resource()).isEqualTo("issuance:0b6f1d2e");
        assertThat(fact.details()).containsExactly(
                Map.entry("attestation_type", "employee.sd.1"), Map.entry("delivery_method", "email"));
        assertThat(fact.source()).isEqualTo("signing");
        assertThat(fact.tenant()).isEqualTo("sandbox");
    }

    @Test
    void missingType_actionOrResource_isRejected() {
        assertThatThrownBy(() -> AuditFact.builder(" ").action("Issue").resource("issuance:1").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event_type");
        assertThatThrownBy(() -> AuditFact.builder("IssuanceCompleted").resource("issuance:1").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("action");
        assertThatThrownBy(() -> AuditFact.builder("IssuanceCompleted").action("Issue").build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("resource");
    }

    @Test
    void blankOptionalSourceOrTenant_isRejected() {
        assertThatThrownBy(() -> fact().source("").build()).hasMessageContaining("source");
        assertThatThrownBy(() -> fact().tenant(" ").build()).hasMessageContaining("tenant");
    }

    @Nested
    class Details {

        @Test
        void none_isAnEmptyObject() {
            assertThat(new AuditFact("IssuanceWithdrawn", "Withdraw", "issuance:1", null, null, null).details())
                    .isEmpty();
        }

        @Test
        void nullValues_areOmittedAtEveryLevel() {
            Map<String, Object> holder = new HashMap<>();
            holder.put("identifier", "12345678Z");
            holder.put("email", null);

            AuditFact fact = fact()
                    .detail("valid_until", null)
                    .detail("holder", holder)
                    .detail("issued_attributes", Arrays.asList("given_name", null, "family_name"))
                    .build();

            assertThat(fact.details()).containsOnlyKeys("holder", "issued_attributes");
            assertThat(fact.details().get("holder")).isEqualTo(Map.of("identifier", "12345678Z"));
            assertThat(fact.details().get("issued_attributes")).isEqualTo(List.of("given_name", "family_name"));
        }

        @Test
        void datesAndUuids_becomeTheirTextInUtc() {
            UUID id = UUID.fromString("5d1c7a40-2f0e-4b8a-9a61-0c3e5f7d2b11");

            AuditFact fact = fact()
                    .detail("valid_from", Instant.parse("2026-10-01T09:12:44Z"))
                    .detail("valid_until", OffsetDateTime.of(2027, 10, 1, 11, 12, 44, 0, ZoneOffset.ofHours(2)))
                    .detail("birth_date", LocalDate.of(1990, 5, 17))
                    .detail("process_id", id)
                    .build();

            assertThat(fact.details()).containsEntry("valid_from", "2026-10-01T09:12:44Z")
                    .containsEntry("valid_until", "2027-10-01T09:12:44Z")
                    .containsEntry("birth_date", "1990-05-17")
                    .containsEntry("process_id", id.toString());
        }

        @Test
        void numbersAndBooleans_areKept() {
            AuditFact fact = fact().detail("returned_count", 3).detail("duration_ms", 12L)
                    .detail("ratio", new BigDecimal("0.5")).detail("accepted_by_holder", true).build();

            assertThat(fact.details()).containsEntry("returned_count", 3).containsEntry("duration_ms", 12L)
                    .containsEntry("ratio", new BigDecimal("0.5")).containsEntry("accepted_by_holder", true);
        }

        @Test
        void unsupportedType_isRejectedWithItsPathButNotItsValue() {
            assertThatThrownBy(() -> fact().detail("holder", Map.of("modality", Modality.QTSP)).build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("details.holder.modality")
                    .hasMessageContaining(Modality.class.getName())
                    .hasMessageNotContaining("QTSP");
        }

        @Test
        void nonFiniteNumber_isRejected() {
            assertThatThrownBy(() -> fact().detail("ratio", Double.NaN).build())
                    .hasMessageContaining("details.ratio is not a finite number");
        }

        @Test
        void nonStringKey_isRejected() {
            assertThatThrownBy(() -> fact().detail("holder", Map.of(1, "x")).build())
                    .hasMessageContaining("details.holder has a key that is not a non-blank string");
        }

        @Test
        void areAnImmutableCopy() {
            List<Object> attributes = new ArrayList<>(List.of("given_name"));
            AuditFact fact = fact().detail("issued_attributes", attributes).build();
            attributes.add("family_name");

            assertThat(fact.details().get("issued_attributes")).isEqualTo(List.of("given_name"));
            assertThatThrownBy(() -> fact.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    private static AuditFact.Builder fact() {
        return AuditFact.builder("IssuanceCompleted").action("Issue").resource("issuance:1");
    }

    private enum Modality { QTSP }
}
