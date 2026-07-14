package com.proactiveguardian.model;

import java.util.List;

/** Port of {@code src/models.py::Finding}. */
public record Finding(
        Severity severity,
        String category,
        String title,
        String detail,
        List<String> evidence,
        double confidence,
        String sourceUrl
) {
    public Finding {
        if (evidence == null) evidence = List.of();
    }

    public Finding(Severity severity, String category, String title,
                   String detail, List<String> evidence, double confidence) {
        this(severity, category, title, detail, evidence, confidence, null);
    }

    public static Finding info(String category, String title, String detail, double confidence) {
        return new Finding(Severity.INFO, category, title, detail, List.of(), confidence);
    }
    public static Finding warn(String category, String title, String detail, double confidence) {
        return new Finding(Severity.WARN, category, title, detail, List.of(), confidence);
    }
    public static Finding block(String category, String title, String detail, double confidence) {
        return new Finding(Severity.BLOCK, category, title, detail, List.of(), confidence);
    }
}

