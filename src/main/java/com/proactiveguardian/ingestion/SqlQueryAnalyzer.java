package com.proactiveguardian.ingestion;

import com.proactiveguardian.model.Artifact;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SQL-query analyser stub — port of the empty
 * {@code src/ingestion/sql_query_analyzer.py} placeholder. Intended to walk
 * SELECT statements to enrich {@code DATA_REFERENCE} artifacts with column
 * projections, joins, and filter predicates.
 */
@Component
public class SqlQueryAnalyzer {

    public List<Artifact> analyseQueries(String sqlSource, String repo) {
        // TODO: implement SELECT/JOIN column-projection extraction (JSqlParser).
        return List.of();
    }
}

