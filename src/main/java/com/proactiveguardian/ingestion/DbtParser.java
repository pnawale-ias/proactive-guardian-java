package com.proactiveguardian.ingestion;

import com.proactiveguardian.model.Artifact;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * dbt-project ingester stub. The Python counterpart
 * ({@code src/ingestion/dbt_parser.py}) is a placeholder in the reference
 * project; this Java port keeps the same signature so {@link GitIngester}
 * can call it, but returns an empty list until the feature is implemented.
 *
 * <p>Enable with {@code guardian.dbt.enabled=true}; otherwise the bean is
 * absent and {@link GitIngester} skips dbt handling entirely.</p>
 */
@Component
@ConditionalOnProperty(name = "guardian.dbt.enabled", havingValue = "true", matchIfMissing = true)
public class DbtParser {

    public boolean isDbtProject(Path root) {
        return java.nio.file.Files.exists(root.resolve("dbt_project.yml"));
    }

    public List<Artifact> parseProject(Path root, String repo,
                                       String defaultCatalog, String defaultSchema, String dialect) {
        // TODO: port from src/ingestion/dbt_parser.py once the Python spec exists.
        return List.of();
    }
}
