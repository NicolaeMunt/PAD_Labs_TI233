# Gateway (smart proxy)

The single entry point of the system (Etapa 2 of the lab). Clients send every request to
the gateway; the gateway sends it to one of the API instances (DW nodes) and returns the
answer. It exposes the same paths as the API (`/api/**`, `/whoami`), so a client only
changes the host.

The API instances are grouped into **warehouses**. Each warehouse has its own MongoDB replica set,
so the gateway first picks the warehouse (`X-Warehouse` header, default `warehouse-1`) and does
everything below only inside it: balancing, failover and caching never cross warehouses.
The gateway also **creates the warehouses**: their API containers and their Mongo nodes (section 4).

```
                      ┌──────────────── gateway ────────────────┐
client ──keep-alive──►│ cache? ─hit─► answer from Redis         │
                      │   │miss                                 │
                      │   ▼                                     │
                      │ coalesce concurrent misses              │
                      │   ▼                                     │
                      │ load balancer ─► healthy backend        │──pooled──► api-w-1 .. api-w-N
                      │   │ no answer                           │ keep-alive
                      │   ▼                                     │
                      │ failover to the next backend (if safe)  │
                      └─────────────────────────────────────────┘
```

Spring Boot 4, Java 25, Apache HttpClient 5 (connection pool), Redis (cache).

## The three functions required by the lab

### 1. Smart proxy – keeping connections

- **Client side:** Tomcat keeps client connections open (`server.tomcat.keep-alive-timeout=60s`,
  up to 1000 requests per connection), and every request runs on a virtual thread, so thousands of
  waiting clients cost almost nothing.
- **Backend side:** `BackendClient` holds a pool of persistent connections to every API instance
  (`gateway.pool.*`: 200 in total, 50 per instance). A request borrows an open connection and
  gives it back; no TCP handshake per request. Idle connections are closed after 15 s and checked
  before reuse.
- `GET /gateway/state` shows the pool (`leased` = in use, `available` = open and idle).

### 2. Caching

- `GET` responses under `/api/` with status 200 are stored in Redis for 30 s (`gateway.cache.ttl`).
- **The key includes the `Accept` header**, so the JSON and XML versions are cached separately.
- **Invalidation by generation:** each collection (e.g. `/api/games`) has a counter
  `gateway:gen:/api/games`. The cache key contains the current value; any `POST`/`PUT`/`DELETE`
  increments it (`INCR`, atomic), so all older entries are never read again and simply expire.
  The counter is read *before* the backend call, so a `GET` that raced with a write is stored under
  the old generation and can never serve stale data.
- **Request coalescing:** if 20 clients miss the same key at once, only one request goes to the
  API; the others wait for its answer (`X-Cache: COALESCED`).
- If Redis is down, the gateway keeps working without the cache (it retries Redis after 5 s).
- Response header `X-Cache`: `HIT`, `MISS`, `COALESCED`, or `BYPASS` (not cacheable).

### 3. Load balancing

- `gateway.strategy=round-robin` (default): instances in turn, one atomic counter, no locks.
- `gateway.strategy=least-connections`: the instance with the fewest requests in flight.
- **Health checks:** every 2 s the gateway calls each instance's `/actuator/health` (which also
  checks MongoDB and Redis). An instance is taken out of rotation after 2 failed checks in a row
  (so one slow check does not make it flap), or at once when a client request to it fails. It is
  put back after one successful check.
- **Failover:** if an instance does not answer, the request is re-sent to the next one when that
  is safe:
  - connection refused / host unknown → nothing was sent → always retried;
  - timeout after sending → retried only for `GET`, `PUT`, `DELETE` (repeating them is harmless);
    a `POST` gets `504`, because the game may already have been created.

### 4. Warehouses and horizontal scaling

A warehouse `w` with N API instances (N = 1..5, chosen by the client) has:

- API containers `api-w-1` .. `api-w-N`;
- its own replica set `rs-w` with **max(3, N)** Mongo nodes `mongo-w-1` .., each with its own
  volume `gamevault-mongo-w-i`; database `gamevault_w`.

API instance i reads from Mongo node i (`readPreference=nearest`, tag `node:i`, any node as a
fallback); all writes go to the primary with `w=majority`. Never fewer than 3 copies, because a
new primary can only be elected while a majority of the copies is alive.

- **Routing:** `X-Warehouse: warehouse-2` sends the request to warehouse-2's instances only;
  no header means `gateway.default-warehouse`. The answer carries `X-Warehouse`.
  Unknown warehouse → `404`, invalid name → `400`, no healthy instance in it → `503`.
  A request is **never failed over to another warehouse**: that instance would answer from a
  different database.
- **Cache per warehouse:** keys and generation counters contain the warehouse
  (`gateway:resp:{warehouse}:...`, `gateway:gen:{warehouse}:{collection}`), so a write in
  warehouse-2 only invalidates warehouse-2.
- **Scaling API** (`WarehouseController`, `WarehouseManager`):

  | Call | Effect |
  |------|--------|
  | `GET /gateway/warehouses` | per warehouse: `phase`, wanted and actual size, API instances (with `hostPort` / `directUrl`), `dbNodes` (PRIMARY / SECONDARY / ...) |
  | `PUT /gateway/warehouses/{name}` `{"replicas": n}` | create the warehouse, or scale it to n API instances + max(3, n) database copies → `202 {"warehouse","before","desiredReplicas","desiredDbCopies"}` |
  | `DELETE /gateway/warehouses/{name}` | remove the warehouse: APIs, Mongo nodes **and their volumes** (its data) → `202` |

- **Desired state, done in the background:** PUT only records the wanted number of API
  instances (the last PUT wins) and schedules a *reconcile* of that warehouse. Each warehouse has
  one reconcile thread, so its steps never overlap; different warehouses scale in parallel.
  `phase` is `SCALING` while it works, `READY` when done, `ERROR` (with `lastError`) if a step
  failed (send the PUT again to retry), `DELETING` while it is being removed.
- **Scale up:** Mongo nodes first. A new warehouse starts its 3+ nodes and initiates the replica
  set, then waits for a primary. An existing one starts each new node, adds it to the replica set
  (one member per reconfig) and the node copies the data. Then the API instances start.
- **Scale down:** API instances with the highest index first: out of the registry (`DRAINING`: no
  new requests), stopped once their requests finished (at most `drain-timeout`). Then the extra
  Mongo nodes leave the replica set (a primary steps down first and a new one is elected) and are
  removed with their volumes.
- **Containers:** `DockerProvisioner` calls the Docker Engine API over the mounted socket
  `/var/run/docker.sock`. API containers come from `gamevault-api:latest` with `APP_WAREHOUSE`,
  `APP_INSTANCE_ID=api-w-i` and the warehouse's connection string, and are published on the first
  free host port in 8081..8099. Mongo nodes come from `mongo:8` (pulled if missing) with
  `--replSet rs-w --wiredTigerCacheSizeGB 0.25`. Everything is on the network `gamevault-net`.
  A new API instance joins the registry as `STARTING` and enters the rotation after its first
  passed health check (Spring needs ~10–20 s to boot).
- **Replica sets:** `MongoReplicaSet` uses the MongoDB Java driver (initiate, add / remove member,
  step down, status). A monitor polls every replica set every few seconds for the `dbNodes` states.
- **Startup and restart:** every container and volume carries the labels `gamevault.managed=true`,
  `gamevault.role` (`api` / `mongo`), `gamevault.warehouse`, `gamevault.index` (and
  `gamevault.host-port` on APIs). On startup the gateway lists them and takes them back, so no
  instance or database copy is lost. Then it creates the warehouses in
  `gateway.initial-warehouses` that do not exist yet (1 API instance + 3 Mongo nodes each).
- Errors: `400` bad name or replicas outside 1..`max-replicas` (`0`: use DELETE),
  `409` too many warehouses, deleting the default warehouse, or a warehouse configured in
  `gateway.backends` (fixed instances), `501` scaling disabled.

## Configuration

`src/main/resources/application.properties`; Docker Compose overrides with environment variables.

| Property | Env variable | Default |
|----------|--------------|---------|
| `gateway.backends` | `GATEWAY_BACKENDS` | fixed instances, `warehouse=URL` or a bare URL for the default warehouse (local runs; not used in compose) |
| `gateway.default-warehouse` | `GATEWAY_DEFAULTWAREHOUSE` | `warehouse-1` |
| `gateway.initial-warehouses` | `GATEWAY_INITIALWAREHOUSES` | empty (`warehouse-1,warehouse-2,warehouse-3` in compose) |
| `gateway.strategy` | `GATEWAY_STRATEGY` | `round-robin` |
| `gateway.provisioner.enabled` | `GATEWAY_PROVISIONER_ENABLED` | `false` (`true` in compose) |
| `gateway.provisioner.api-image` / `mongo-image` | `GATEWAY_PROVISIONER_APIIMAGE` / `_MONGOIMAGE` | `gamevault-api:latest` / `mongo:8` |
| `gateway.provisioner.network` | `GATEWAY_PROVISIONER_NETWORK` | `gamevault-net` |
| `gateway.provisioner.host-port-from` / `-to` | `GATEWAY_PROVISIONER_HOSTPORTFROM` / `_HOSTPORTTO` | `8081` / `8099` |
| `gateway.provisioner.mongo-cache-gb` | `GATEWAY_PROVISIONER_MONGOCACHEGB` | `0.25` |
| `gateway.provisioner.redis-host` / `redis-port` | `GATEWAY_PROVISIONER_REDISHOST` / `_REDISPORT` | what the API instances connect to |
| `gateway.provisioner.min-db-copies` | `GATEWAY_PROVISIONER_MINDBCOPIES` | `3` |
| `gateway.provisioner.max-replicas` / `max-warehouses` | `GATEWAY_PROVISIONER_MAXREPLICAS` / `_MAXWAREHOUSES` | `5` / `5` |
| `gateway.provisioner.drain-timeout` / `mongo-timeout` | | `15s` / `90s` |
| `gateway.health.interval` / `timeout` | `GATEWAY_HEALTH_INTERVAL` / `GATEWAY_HEALTH_TIMEOUT` | `2s` / `2s` |
| `gateway.health.failures-before-down` | `GATEWAY_HEALTH_FAILURESBEFOREDOWN` | `2` |
| `gateway.pool.response-timeout` | `GATEWAY_POOL_RESPONSE_TIMEOUT` | `10s` |
| `gateway.cache.enabled` / `ttl` | `GATEWAY_CACHE_ENABLED` / `GATEWAY_CACHE_TTL` | `true` / `30s` |

## Demo

```bash
docker compose up --build

# warehouses: each answers from its own database
# warehouses: each answers from its own database
curl -s -H "X-Warehouse: warehouse-2" localhost:8080/whoami

# horizontal scaling: warehouse-1 gets 3 API instances (its replica set already has 3 copies)
curl -s -X PUT localhost:8080/gateway/warehouses/warehouse-1 -H "Content-Type: application/json" -d '{"replicas":3}'
curl -s localhost:8080/gateway/warehouses      # wait until phase is READY and the instances are UP

# load balancing: the instance changes on every call (api-warehouse-1-1, -2, -3)
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -D - localhost:8080/whoami | grep X-Instance-Id; done

# caching: first MISS, then HIT (same X-Instance-Id: the one that produced the cached answer)
curl -s -D - -o /dev/null localhost:8080/api/games | grep -E "X-Cache|X-Instance"
curl -s -D - -o /dev/null localhost:8080/api/games | grep -E "X-Cache|X-Instance"
curl -s -D - -o /dev/null -H "Accept: application/xml" localhost:8080/api/games | grep X-Cache   # MISS: XML is separate

# a write invalidates the cache
curl -s -X POST localhost:8080/api/games -H "Content-Type: application/json" \
  -d '{"title":"Doom","developer":"id Software","genre":"ACTION","releaseYear":1993}'
curl -s -D - -o /dev/null localhost:8080/api/games | grep X-Cache                              # MISS

# failover: stop one instance of warehouse-1, requests keep working on the other two
docker stop api-warehouse-1-2
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " localhost:8080/whoami; done
curl -s localhost:8080/gateway/state        # api-warehouse-1-2 healthy=false
docker start api-warehouse-1-2              # back in rotation after its health check passes

# 5 instances = 5 database copies: nodes 4 and 5 join rs-warehouse-1 and copy the data
curl -s -X PUT localhost:8080/gateway/warehouses/warehouse-1 -H "Content-Type: application/json" -d '{"replicas":5}'

# scale back down: APIs 2..5 drain and stop, then Mongo nodes 4 and 5 leave the replica set
curl -s -X PUT localhost:8080/gateway/warehouses/warehouse-1 -H "Content-Type: application/json" -d '{"replicas":1}'
```

## Tests

```bash
cd lab3 && ./mvnw -f gateway/pom.xml test
```

They run against stub API instances started inside the test (no Docker, Redis or Mongo needed):
round-robin order and fairness under 16 threads, least-connections, failover rules (GET retried after
timeout, POST not retried after timeout, POST retried after connection refused), cache hit / miss,
JSON vs XML keys, invalidation, coalescing of 20 concurrent misses into 1 backend call, 300 parallel
requests split exactly 100/100/100, and the full Spring Boot app over HTTP.

Warehouses and scaling: routing by `X-Warehouse` (never failing over to another warehouse, 404/400
for unknown/invalid names), cache and invalidation separate per warehouse, 400 requests answered
while an instance is removed and another added (the drained one gets no request after its drain),
the retire/drain hand-off under 8 threads (repeated 20 times), the warehouse manager (with a fake
provisioner and replica set), `DockerProvisioner` against a fake Docker Engine on a real Unix
socket, and the replica-set manager.
