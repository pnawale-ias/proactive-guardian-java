# SQL Schema Ingestion Plan

## Goal

Connect to a configured MySQL database, introspect its full schema via
`INFORMATION_SCHEMA` (tables, columns with all metadata, FK relationships), and
persist every piece of knowledge to both Qdrant (vector search) and Neo4j
(dependency graph) using this repo's existing patterns.

---

## Reference: collective-mind

- `SqlSchemaIntrospector` — raw `DriverManager.getConnection()`, 3 INFORMATION_SCHEMA queries
- `SqlSchemaIngestionService` — converts blueprint → atoms → dual-write
- Pattern difference: collective-mind uses its own `SemanticAtom`/`GraphBuilderService`; here we use `Artifact` + `VectorStore` + `GraphStore` directly (same as `ConfluenceIngester`)

---

## What gets persisted

| Source | ArtifactType | Content stored | Metadata keys |
|---|---|---|---|
| Every `BASE TABLE` | `SQL_TABLE` | `col1 type, col2 type, …` (all columns + types) | `table_fqn`, `schema`, `table`, `kind="TABLE"`, `dialect="mysql"`, `column_count` |
| Every column | `SQL_COLUMN` | data type string | `table_id`, `table_fqn`, `column`, `data_type`, `ordinal`, `nullable`, `column_key`, `column_default`, `extra`, `dialect="mysql"` |
| Every FK constraint | Neo4j edge only | — | `REFERENCES` relationship: `(tableNode)-[:REFERENCES]->(refTableNode)` |

SQL_COLUMN artifacts carry `table_id` in metadata — `Neo4jGraphStore.upsertArtifact`
already auto-creates the `HAS_COLUMN` edge from parent table to each column when
it sees `table_id` in the metadata, so no extra linking code is needed.

FK edges are written via `gs.link(tableArtifactId, refTableArtifactId, "REFERENCES")`.

---

## INFORMATION_SCHEMA queries

**1. Tables**
```sql
SELECT TABLE_NAME
FROM INFORMATION_SCHEMA.TABLES
WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
```

**2. Columns (full metadata — more than collective-mind's 2-column version)**
```sql
SELECT COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION,
       IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, EXTRA
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
ORDER BY ORDINAL_POSITION
```

**3. Foreign keys**
```sql
SELECT TABLE_NAME, COLUMN_NAME,
       REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME
FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL
```

---

## Files to Create / Modify

### 1. `pom.xml` — add MySQL JDBC driver

```xml
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

Spring Boot's BOM manages the version automatically.

### 2. `src/main/resources/application.yml` — new guardian properties

Under the existing `guardian:` block:

```yaml
  mysql-enabled:  ${MYSQL_ENABLED:false}
  mysql-url:      ${MYSQL_URL:}
  mysql-user:     ${MYSQL_USER:}
  mysql-password: ${MYSQL_PASSWORD:}
  mysql-database: ${MYSQL_DATABASE:}
```

### 3. `src/main/java/com/proactiveguardian/config/GuardianProperties.java` — 5 new fields

Add after `databricksDefaultCatalog`:

```java
Boolean mysqlEnabled,
String mysqlUrl,
String mysqlUser,
String mysqlPassword,
String mysqlDatabase,
```

Add null-guard in compact constructor: `if (mysqlEnabled == null) mysqlEnabled = false;`

### 4. `src/main/java/com/proactiveguardian/ingestion/MysqlSchemaIngester.java` — new file

```
package com.proactiveguardian.ingestion;

@Component
@ConditionalOnProperty(name = "guardian.mysql-enabled", havingValue = "true")
public class MysqlSchemaIngester {
    // deps: VectorStore vs, GraphStore gs, GuardianProperties props

    public int ingestSchema() { ... }
    // Returns total artifact count (tables + columns)

    private static String hash(String input) { ... }
    // SHA-256 first-8-bytes hex — identical to ConfluenceIngester.hash()
}
```

**`ingestSchema()` algorithm:**

```
1.  DriverManager.getConnection(props.mysqlUrl(), props.mysqlUser(), props.mysqlPassword())
    — try-with-resources, single short-lived connection

2.  Query 1 → List<String> tableNames

3.  For each tableName:
      Query 2 → List<ColumnInfo> (name, dataType, ordinal, nullable, columnKey, default, extra)
      Build tableId  = hash("mysql:" + db + "." + tableName)
      Build SQL_TABLE Artifact:
        id      = tableId
        type    = ArtifactType.SQL_TABLE
        name    = tableName
        content = "col1 varchar, col2 int, …"   ← all columns "name type" joined by ", "
        lang    = null
        repo    = props.mysqlDatabase()
        path    = db + "/" + tableName
        url     = null
        meta    = {table_fqn: db+"."+table, schema: db, table: tableName,
                   kind: "TABLE", dialect: "mysql", column_count: N}

      For each column:
        Build SQL_COLUMN Artifact:
          id      = hash("mysql:" + db + "." + tableName + "." + colName)
          type    = ArtifactType.SQL_COLUMN
          name    = tableName + "." + colName
          content = dataType
          lang    = null
          repo    = props.mysqlDatabase()
          path    = db + "/" + tableName + "/" + colName
          url     = null
          meta    = {table_id: tableId, table_fqn: db+"."+table,
                     column: colName, data_type: dataType,
                     ordinal: ordinal, nullable: isNullable,
                     column_key: columnKey, column_default: columnDefault,
                     extra: extra, dialect: "mysql"}

4.  Query 3 → Map<String, List<ForeignKey>>  (table → list of FKs)

5.  vs.upsert(allArtifacts)                 — one bulk call to Qdrant
6.  for each artifact: gs.upsertArtifact(a) — Neo4j MERGE
    (Neo4jGraphStore auto-adds HAS_COLUMN edge via meta.get("table_id"))

7.  For each FK: gs.link(tableId, refTableId, "REFERENCES")
    (refTableId = hash("mysql:" + db + "." + referencedTableName))

8.  log.info("MySQL ingest done: db={} tables={} artifacts={}",
            db, tableCount, allArtifacts.size());
9.  return allArtifacts.size()
```

### 5. `src/main/java/com/proactiveguardian/web/IngestController.java` — new endpoint

Inject `ObjectProvider<MysqlSchemaIngester> mysql` in constructor. Add:

```java
@PostMapping("/mysql")
public Map<String, Object> mysql() {
    MysqlSchemaIngester mi = mysql.getIfAvailable();
    if (mi == null) {
        return Map.of("ok", false,
            "reason", "mysql disabled — set MYSQL_ENABLED=true + MYSQL_URL/USER/PASSWORD/DATABASE");
    }
    try {
        int count = mi.ingestSchema();
        return Map.of("ok", true, "artifacts_ingested", count);
    } catch (Exception ex) {
        log.warn("MySQL schema ingest failed: {}", ex.toString());
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("error", ex.getClass().getSimpleName());
        err.put("message", String.valueOf(ex.getMessage()));
        return err;
    }
}
```

---

## Pointing at a specific database

All connection params are driven by environment variables. Set in `.env`:

```bash
MYSQL_ENABLED=true
MYSQL_URL=jdbc:mysql://host:3306/mydb
MYSQL_USER=readonly_user
MYSQL_PASSWORD=secret
MYSQL_DATABASE=mydb   # the schema name passed to INFORMATION_SCHEMA queries
```

`MYSQL_DATABASE` scopes all queries to exactly that schema, so only the tables
you care about are ingested even if the MySQL user has access to multiple schemas.

---

## Verification

```bash
# 1. Compile
./mvnw compile

# 2. Unit tests (MysqlSchemaIngester bean is skipped when mysql-enabled=false)
./mvnw test

# 3. Live ingest
#    Set env vars above, then:
make down && make up
curl -si -X POST http://localhost:8080/ingest/mysql
# Success: {"ok":true,"artifacts_ingested":N}
# Failure: {"ok":false,"error":"...","message":"..."}

# 4. Verify in Neo4j browser (bolt://localhost:7687)
MATCH (t:Artifact {type:"sql_table"}) RETURN t.name, t.table_fqn LIMIT 20
MATCH (t:Artifact {type:"sql_table"})-[:HAS_COLUMN]->(c:Artifact {type:"sql_column"})
      RETURN t.name, c.name, c.data_type LIMIT 50
MATCH (a:Artifact)-[:REFERENCES]->(b:Artifact) RETURN a.name, b.name LIMIT 20

# 5. Verify in Qdrant
curl http://localhost:6333/collections/code_and_docs/points/scroll \
  -H "Content-Type: application/json" \
  -d '{"filter":{"must":[{"key":"type","match":{"value":"sql_table"}}]},"limit":10}'
```
