package com.proactiveguardian.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of {@code src/models.py::SchemaDelta}. Mutable-list fields keep the
 * builder-style code in {@code SqlDiffService} readable; consumers should
 * treat the delta as read-only after {@code SqlDiffService.diff(...)} returns.
 */
public record SchemaDelta(
        List<String> addedTables,
        List<String> droppedTables,
        List<TableRename> renamedTables,
        List<ColumnChange> addedColumns,
        List<ColumnChange> droppedColumns,
        List<ColumnRename> renamedColumns,
        List<ColumnChange> typeChanges,
        List<ColumnChange> nullabilityChanges,
        Confidence parseConfidence
) {
    public enum Confidence { HIGH, LOW }

    /** Simple pair to replace the Python {@code list[tuple[str, str]]}. */
    public record TableRename(String oldName, String newName) {}

    public SchemaDelta {
        if (addedTables == null) addedTables = new ArrayList<>();
        if (droppedTables == null) droppedTables = new ArrayList<>();
        if (renamedTables == null) renamedTables = new ArrayList<>();
        if (addedColumns == null) addedColumns = new ArrayList<>();
        if (droppedColumns == null) droppedColumns = new ArrayList<>();
        if (renamedColumns == null) renamedColumns = new ArrayList<>();
        if (typeChanges == null) typeChanges = new ArrayList<>();
        if (nullabilityChanges == null) nullabilityChanges = new ArrayList<>();
        if (parseConfidence == null) parseConfidence = Confidence.HIGH;
    }

    public static SchemaDelta empty() {
        return new SchemaDelta(null, null, null, null, null, null, null, null, Confidence.HIGH);
    }

    public boolean isEmpty() {
        return addedTables.isEmpty()
                && droppedTables.isEmpty()
                && renamedTables.isEmpty()
                && addedColumns.isEmpty()
                && droppedColumns.isEmpty()
                && renamedColumns.isEmpty()
                && typeChanges.isEmpty()
                && nullabilityChanges.isEmpty();
    }
}

