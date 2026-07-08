package com.proactiveguardian.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.EnumSet;
import java.util.Set;

/**
 * Kind of artifact stored in the knowledge graph / vector store.
 *
 * <p>Wire values are lower-snake_case to match {@code src/models.py::ArtifactType}
 * so payloads round-trip through Qdrant/Neo4j without translation.</p>
 */
public enum ArtifactType {
    CODE_SYMBOL("code_symbol"),
    API_ENDPOINT("api_endpoint"),
    CONFLUENCE_PAGE("confluence_page"),
    CONSTRAINT("constraint"),
    FEATURE("feature"),
    // ---- Data-plane artifacts (see DATA_ARTIFACT_TYPES) -----------------
    SQL_TABLE("sql_table"),
    SQL_COLUMN("sql_column"),
    SQL_VIEW("sql_view"),
    DBT_MODEL("dbt_model"),
    DATABRICKS_NOTEBOOK("databricks_notebook"),
    DATABRICKS_PIPELINE("databricks_pipeline"),
    ORM_MODEL("orm_model"),
    DATA_REFERENCE("data_reference");

    private static final Set<ArtifactType> DATA_ARTIFACT_TYPES = EnumSet.of(
            SQL_TABLE, SQL_COLUMN, SQL_VIEW,
            DBT_MODEL, DATABRICKS_NOTEBOOK, DATABRICKS_PIPELINE,
            ORM_MODEL, DATA_REFERENCE
    );

    private final String wire;

    ArtifactType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** True for artifacts routed to {@code SchemaChangeDetector}, false for the code plane. */
    public boolean isDataArtifact() {
        return DATA_ARTIFACT_TYPES.contains(this);
    }

    public static ArtifactType fromWire(String value) {
        for (ArtifactType t : values()) {
            if (t.wire.equalsIgnoreCase(value)) return t;
        }
        throw new IllegalArgumentException("Unknown ArtifactType wire value: " + value);
    }
}
