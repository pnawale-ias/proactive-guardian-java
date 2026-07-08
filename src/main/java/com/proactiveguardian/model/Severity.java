package com.proactiveguardian.model;

/**
 * Severity ladder used across all detectors.
 * Wire form is lower-case to match the Python strings.
 */
public enum Severity {
    INFO, WARN, BLOCK;

    public String wire() {
        return name().toLowerCase();
    }

    public static Severity fromWire(String value) {
        return Severity.valueOf(value.toUpperCase());
    }
}

