package com.eudistack.auditoria.domain;

/**
 * Argument checks shared by the records of the domain.
 */
final class Require {

    private Require() {
    }

    static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static <T> T present(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank; leave it null when unknown");
        }
        return value;
    }
}
