package com.proactiveguardian.model;

/** Port of {@code src/models.py::ColumnChange}. */
public record ColumnChange(
        String tableFqn,
        String column,
        String oldType,
        String newType,
        Boolean oldNullable,
        Boolean newNullable,
        boolean isNarrowing
) {
    public static ColumnChange added(String tableFqn, String column, String newType, Boolean newNullable) {
        return new ColumnChange(tableFqn, column, null, newType, null, newNullable, false);
    }

    public static ColumnChange dropped(String tableFqn, String column, String oldType, Boolean oldNullable) {
        return new ColumnChange(tableFqn, column, oldType, null, oldNullable, null, false);
    }
}

