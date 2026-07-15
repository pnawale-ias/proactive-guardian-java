# Knowledge Base Backup & Restore

Covers Qdrant (vector store) and Neo4j (graph store). Use this to preserve
ingested data after a costly ingest run and transfer it to another machine.

---

## Option A — Docker Volume Tar (recommended)

Backs up both stores in one file. Requires containers to be stopped.

### Backup

```bash
make infra-down   # stop Qdrant + Neo4j, keep volumes

docker run --rm \
  -v guardian_qdrant-data:/qdrant-data \
  -v guardian_neo4j-data:/neo4j-data \
  -v $(pwd)/backup:/backup \
  alpine sh -c "mkdir -p /backup && tar czf /backup/guardian-kb-$(date +%Y%m%d).tar.gz /qdrant-data /neo4j-data"

make infra-up     # bring infra back up
```

The archive lands in `./backup/guardian-kb-YYYYMMDD.tar.gz`.

### Restore (same or different machine)

```bash
# Copy the tar file to the target machine first, then:
docker volume create guardian_qdrant-data
docker volume create guardian_neo4j-data

docker run --rm \
  -v guardian_qdrant-data:/qdrant-data \
  -v guardian_neo4j-data:/neo4j-data \
  -v $(pwd)/backup:/backup \
  alpine tar xzf /backup/guardian-kb-YYYYMMDD.tar.gz -C /

make infra-up
```

---

## Option B — Qdrant Snapshot API (collection-level, live)

Use this when you only want to transfer specific Qdrant collections without
stopping the infrastructure.

### Backup

```bash
# 1. Create snapshot
curl -X POST http://localhost:6333/collections/code_and_docs/snapshots

# 2. List snapshots to get the filename
curl http://localhost:6333/collections/code_and_docs/snapshots

# 3. Download to disk (replace <snapshot-name> with the value from step 2)
curl -o code_and_docs.snapshot \
  "http://localhost:6333/collections/code_and_docs/snapshots/<snapshot-name>"
```

### Restore

```bash
# Upload the snapshot to the target Qdrant instance
curl -X POST "http://localhost:6333/collections/code_and_docs/snapshots/upload" \
  -H "Content-Type: multipart/form-data" \
  -F "snapshot=@code_and_docs.snapshot"
```

---

## Option C — Neo4j Dump (offline)

Use when you want a portable Neo4j-native dump independent of Docker.

### Backup

```bash
# Stop neo4j (dump requires the DB to be offline)
docker stop guardian-neo4j

docker exec guardian-neo4j neo4j-admin database dump neo4j \
  --to-path=/tmp/neo4j-backup.dump

docker cp guardian-neo4j:/tmp/neo4j-backup.dump ./neo4j-backup.dump

docker start guardian-neo4j
```

### Restore

```bash
docker cp ./neo4j-backup.dump guardian-neo4j:/tmp/neo4j-backup.dump

docker stop guardian-neo4j

docker exec guardian-neo4j neo4j-admin database load neo4j \
  --from-path=/tmp/neo4j-backup.dump --overwrite-destination=true

docker start guardian-neo4j
```

---

## Notes

- Volume names (`guardian_qdrant-data`, `guardian_neo4j-data`) are set by the
  `name: guardian` directive in `docker-compose.yml`. If you cloned the repo
  into a differently-named directory, verify with `docker volume ls`.
- The Qdrant collection name (`code_and_docs`) is configured at
  `guardian.qdrant-collection` in `application.yml`.
- Neo4j default credentials: `neo4j / guardianpass` (set in `docker-compose.yml`).
- After restore, re-run `POST /ingest/mysql` only if the MySQL schema has changed
  since the backup was taken — no need to re-embed otherwise.
