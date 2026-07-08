package com.proactiveguardian.ingestion.sql;

import com.proactiveguardian.model.SchemaDelta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Port of {@code tests/test_sql_diff.py} — focused on the observable delta. */
class SqlDiffServiceTest {

    private final SqlDiffService sql = new SqlDiffService();

    @Test
    void detectsAddedColumn() {
        String before = "CREATE TABLE users (id INT NOT NULL, email VARCHAR(100));";
        String after  = "CREATE TABLE users (id INT NOT NULL, email VARCHAR(100), created_at TIMESTAMP);";

        SchemaDelta delta = sql.diff(before, after);
        assertThat(delta.addedColumns()).extracting("column").containsExactly("created_at");
        assertThat(delta.droppedColumns()).isEmpty();
    }

    @Test
    void detectsDroppedColumn() {
        String before = "CREATE TABLE users (id INT NOT NULL, email VARCHAR(100), phone VARCHAR(20));";
        String after  = "CREATE TABLE users (id INT NOT NULL, email VARCHAR(100));";

        SchemaDelta delta = sql.diff(before, after);
        assertThat(delta.droppedColumns()).extracting("column").containsExactly("phone");
    }

    @Test
    void detectsNarrowingType() {
        String before = "CREATE TABLE users (email VARCHAR(200));";
        String after  = "CREATE TABLE users (email VARCHAR(50));";

        SchemaDelta delta = sql.diff(before, after);
        assertThat(delta.typeChanges())
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.isNarrowing()).isTrue();
                    assertThat(c.oldType()).contains("200");
                    assertThat(c.newType()).contains("50");
                });
    }

    @Test
    void widenIntToBigintIsNotNarrowing() {
        String before = "CREATE TABLE t (n INT);";
        String after  = "CREATE TABLE t (n BIGINT);";

        SchemaDelta delta = sql.diff(before, after);
        assertThat(delta.typeChanges()).singleElement()
                .extracting("isNarrowing").isEqualTo(false);
    }

    @Test
    void addedAndDroppedTables() {
        String before = "CREATE TABLE a (id INT); CREATE TABLE b (id INT);";
        String after  = "CREATE TABLE b (id INT); CREATE TABLE c (id INT);";

        SchemaDelta delta = sql.diff(before, after);
        assertThat(delta.addedTables()).containsExactly("c");
        assertThat(delta.droppedTables()).containsExactly("a");
    }

    @Test
    void emptyBothSides() {
        SchemaDelta delta = sql.diff("", "");
        assertThat(delta.isEmpty()).isTrue();
    }
}

