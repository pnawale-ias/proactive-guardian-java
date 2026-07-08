package com.proactiveguardian.ingestion;

import com.proactiveguardian.model.Artifact;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Databricks Unity Catalog snapshot ingester stub. Mirrors
 * {@code src/ingestion/databricks_ingester.py} (a placeholder in the
 * reference project).
 *
 * <p>Enable with {@code guardian.databricks.enabled=true} and a valid
 * {@code guardian.databricks-host} / {@code guardian.databricks-token}.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.databricks.enabled", havingValue = "true")
public class DatabricksIngester {

    public List<Artifact> snapshotCatalog(String catalog) {
        // TODO: use databricks-sdk-java's UnityCatalog client to enumerate
        //       schemas / tables / columns and emit SQL_TABLE + SQL_COLUMN.
        return List.of();
    }
}

