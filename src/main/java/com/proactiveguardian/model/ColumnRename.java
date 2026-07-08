package com.proactiveguardian.model;

/** Port of {@code src/models.py::ColumnRename}. */
public record ColumnRename(
        String tableFqn,
        String oldName,
        String newName,
        double confidence
) {
    public ColumnRename(String tableFqn, String oldName, String newName) {
        this(tableFqn, oldName, newName, 0.7d);
    }
}

