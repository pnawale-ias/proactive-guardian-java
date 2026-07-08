# Qdrant (local via Colima)

## 1. Start Colima
```bash
colima start
# verify docker points to colima
docker context ls
```

## 2. Start Qdrant
```bash
cd docker/qdrant
docker compose up -d
docker compose ps
docker compose logs -f qdrant
```

## 3. Access

| Interface   | URL / Host                     |
|-------------|--------------------------------|
| REST API    | http://localhost:6333          |
| Web UI      | http://localhost:6333/dashboard|
| gRPC        | localhost:6334                 |
| Health      | http://localhost:6333/healthz  |
| Collections | http://localhost:6333/collections |

Quick checks:
```bash
curl http://localhost:6333/healthz
curl http://localhost:6333/collections
```

## 4. Create a test collection
```bash
curl -X PUT http://localhost:6333/collections/test \
  -H "Content-Type: application/json" \
  -d '{
    "vectors": { "size": 4, "distance": "Cosine" }
  }'
```

## 5. Stop / clean up
```bash
docker compose down          # stop
docker compose down -v       # stop + delete stored vectors
```

## Troubleshooting

- `localhost` unreachable → confirm Colima is running: `colima status`
- Port already in use → `lsof -i :6333`
- Reset Colima network: `colima stop && colima start`
- From another container on the same compose network, use host `qdrant:6333` / `qdrant:6334`

