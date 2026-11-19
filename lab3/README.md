# Lab 3 – Game Catalog API

Spring Boot 4 (Java 25) REST API for a catalog of video games.
Games are stored in **MongoDB** and single-game reads are cached in **Redis**.
Clients reach the API through a **gateway** (smart proxy) that routes each request to a
**warehouse**, load-balances over that warehouse's API instances and caches responses.
The gateway also **creates** the warehouses: their API containers and their databases.
See [gateway/README.md](gateway/README.md).

```
                         X-Warehouse
client ──► gateway :8080 ──┬─► warehouse-1: api-warehouse-1-1 .. -N ──► replica set rs-warehouse-1: mongo-warehouse-1-1 .. -M
           (Redis cache)   ├─► warehouse-2: api-warehouse-2-1 .. -N ──► replica set rs-warehouse-2: mongo-warehouse-2-1 .. -M
           creates all     ├─► warehouse-3: ...                     ──► rs-warehouse-3: ...
           containers      └─► warehouse-X (created on request)     ──► rs-warehouse-X: ...
                                                                         M = max(3, N), 1 primary + secondaries
```

- **Warehouse** = N API instances (1..5, chosen by the client) plus **its own MongoDB replica set**
  `rs-<warehouse>` with as many database copies as API instances, but **at least 3**.
  The database is `gamevault_<warehouse>`. Warehouses do not share data.
- **API instance i reads from database copy i** (the nearest member tagged `node:i`; any other copy
  while that one is not readable). **All writes go to the replica set's primary**, and count only
  once a majority of the copies has them (`w=majority`).
- **Horizontal scaling:** the client asks for N instances (`PUT /gateway/warehouses/{name}`
  `{"replicas": N}`, or the web UI) and gets N API containers and max(3, N) Mongo nodes.
  The gateway starts them itself, through the Docker socket.
- **Replication:** if a warehouse's primary dies, the remaining copies elect a new one and the
  warehouse keeps working after a short pause. That needs a majority alive: 3 copies survive
  1 failure, 5 copies survive 2. That is why a warehouse never has fewer than 3 copies.

| Asked for (API instances) | API containers | Database copies | Copies that may fail |
|---------------------------|----------------|-----------------|----------------------|
| 1 | 1 | 3 | 1 |
| 3 | 3 | 3 | 1 |
| 4 | 4 | 4 | 1 |
| 5 | 5 | 5 | 2 |

## Run

```
docker compose up --build
```

Compose starts only `gateway` (port 8080), `web` (5173) and `redis` (6379), plus `api-image`,
which builds the API image and exits. On startup the gateway creates `warehouse-1`,
`warehouse-2` and `warehouse-3` (`GATEWAY_INITIALWAREHOUSES`), each with 1 API instance and
3 Mongo nodes. That is 3 API containers and 9 `mongod` containers. Each `mongod` has a 0.25 GB
WiredTiger cache and uses roughly 150–250 MB of RAM. The first start takes about a minute:
Mongo nodes start, each replica set elects a primary, then the APIs boot.
Watch it with `GET localhost:8080/gateway/warehouses` (`phase` goes `SCALING` → `READY`).

If the gateway restarts, it finds its containers again by their labels and takes them back.

The data of the old shared replica set `rs0` (volumes `gamevault_mongo-1-data`, `-2-data`,
`-3-data`) is not migrated. You can delete those volumes.

### Stop

Compose does not manage the containers and volumes the gateway created, so remove them first.
Removing the volumes **deletes all warehouse data**; skip that line to keep it.

```bash
docker rm -f $(docker ps -aq --filter label=gamevault.managed=true)
docker volume rm $(docker volume ls -q --filter label=gamevault.managed=true)
docker compose down
```

PowerShell:

```powershell
docker rm -f (docker ps -aq --filter label=gamevault.managed=true)
docker volume rm (docker volume ls -q --filter label=gamevault.managed=true)
docker compose down
```

Every response includes an `X-Instance-Id` header that shows which API instance answered,
an `X-Warehouse` header with the warehouse it came from, and an `X-Cache` header
(`HIT`, `MISS`, `COALESCED`, `BYPASS`) set by the gateway.

### Calling an API instance directly

Every API instance is also published on the host, on the first free port in **8081..8099**.
`GET /gateway/warehouses` shows it per instance (`hostPort`, `directUrl`), e.g.
`http://localhost:8081/whoami`. The ports are given out in creation order, so check the list
instead of guessing.

No `X-Warehouse` header is needed: each instance always uses its own warehouse's database.
Direct calls skip the gateway, so no load balancing, failover or gateway cache. A write made
directly does not clear the gateway's cache, so the same `GET` through the gateway can show the
old data for up to 30 s (`gateway.cache.ttl`).

## Warehouses and horizontal scaling

Pick the warehouse with the `X-Warehouse` header; without it, requests go to `warehouse-1`.

```bash
curl -H "X-Warehouse: warehouse-2" localhost:8080/whoami      # {"warehouse":"warehouse-2","database":"gamevault_warehouse-2",...}

# warehouse-2: 5 API instances + 5 database copies (2 new Mongo nodes copy the data, then 4 new APIs start)
curl -X PUT localhost:8080/gateway/warehouses/warehouse-2 -H "Content-Type: application/json" -d '{"replicas":5}'
# → 202 {"warehouse":"warehouse-2","before":1,"desiredReplicas":5,"desiredDbCopies":5}

# a new warehouse with 2 API instances (+ 3 database copies, the minimum)
curl -X PUT localhost:8080/gateway/warehouses/warehouse-4 -H "Content-Type: application/json" -d '{"replicas":2}'

curl localhost:8080/gateway/warehouses      # phase SCALING → READY; instances STARTING → UP; dbNodes STARTUP2 → SECONDARY

# scale down: APIs 4 and 5 drain and stop, then Mongo nodes 4 and 5 leave the replica set and are removed
curl -X PUT localhost:8080/gateway/warehouses/warehouse-2 -H "Content-Type: application/json" -d '{"replicas":3}'

# remove a warehouse: its APIs, its Mongo nodes AND their volumes (its data is deleted)
curl -X DELETE localhost:8080/gateway/warehouses/warehouse-4
```

In Postman: `PUT http://localhost:8080/gateway/warehouses/warehouse-2`, Body → raw → JSON
`{"replicas": 5}`. Then send `GET http://localhost:8080/gateway/warehouses` until `phase` is `READY`.

The PUT only records the wanted size and answers **202** at once. The gateway does the work in
the background, one step at a time per warehouse:

- **Scale up:** Mongo nodes first. Each new node is added to the replica set and copies the
  data. Then the new API instances start.
- **Scale down:** the API instances with the highest numbers are drained (no new requests,
  running ones finish) and stopped. Then the extra Mongo nodes leave the replica set and are
  removed with their volumes. If a node being removed is the primary, it steps down first and
  the others elect a new primary.

If two PUTs come in quickly, the last one wins.

The web UI (http://localhost:5173) has the same controls: per warehouse its phase, API instances
(with their direct port), database copies (PRIMARY / SECONDARY), +/−, "Add warehouse",
"Remove", and a selector that sends all calls to the chosen warehouse.

Rules and errors:

| Case | Answer |
|------|--------|
| `replicas` outside 1..5 (`0`: use DELETE), or an invalid name | `400` |
| more than 5 warehouses | `409` |
| deleting the default warehouse (`warehouse-1`) | `409` |
| a warehouse configured in `gateway.backends` (fixed instances) | `409` |
| scaling disabled (gateway not running in Docker) | `501` |

A new warehouse's database is empty; MongoDB creates it, with its unique index, on first use.

**Trade-off of reading from "your own" copy:** a secondary is usually a few milliseconds behind
the primary. A read through another instance right after a write can, rarely, miss that write.

## Replication (one replica set per warehouse)

```bash
docker exec mongo-warehouse-1-1 mongosh --quiet --eval "rs.status().members.map(m => m.name + ' ' + m.stateStr)"
# [ 'mongo-warehouse-1-1:27017 PRIMARY', 'mongo-warehouse-1-2:27017 SECONDARY', 'mongo-warehouse-1-3:27017 SECONDARY' ]
```

`GET /gateway/warehouses` shows the same under `dbNodes`.

Failover demo (use the node that is PRIMARY in your output):

```bash
docker stop mongo-warehouse-1-1      # stop the primary
# ~10 s later another node is PRIMARY:
docker exec mongo-warehouse-1-2 mongosh --quiet --eval "rs.status().members.map(m => m.name + ' ' + m.stateStr)"
curl -X POST localhost:8080/api/games -H "X-Warehouse: warehouse-1" -H "Content-Type: application/json" \
  -d '{"title":"Quake","developer":"id Software","genre":"ACTION","releaseYear":1996}'
# a 503 during the election is expected (the APIs report unhealthy while there is no primary); retry
docker start mongo-warehouse-1-1     # it rejoins as a SECONDARY and copies what it missed
```

Each API instance connects with all of its warehouse's nodes, e.g. for instance 2 of warehouse-1:
`mongodb://mongo-warehouse-1-1:27017,mongo-warehouse-1-2:27017,mongo-warehouse-1-3:27017/?replicaSet=rs-warehouse-1&w=majority&retryWrites=true&readPreference=nearest&readPreferenceTags=node:2&readPreferenceTags=`.
The driver finds the new primary by itself, and `w=majority` means a write is only acknowledged
once a majority of the copies has it, so a failover never loses an acknowledged write.

## Endpoints

Base URL: `http://localhost:8080` (the gateway; it exposes the same paths as the API)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/games` | List games (paged). Optional `genre`, `page`, `size`, `sort` query params. |
| GET | `/api/games/{id}` | Get one game by id. Cached in Redis. |
| POST | `/api/games` | Create a game. Returns `201`. |
| PUT | `/api/games/{id}` | Replace a game's fields. Clears its cache entry. |
| DELETE | `/api/games/{id}` | Soft-delete a game (it is hidden, not removed). Returns `204`. |
| GET | `/whoami` | Shows the instance id, warehouse name and database name. |
| GET | `/actuator/health` | Health of the gateway. (Each API instance has its own, polled by the gateway.) |
| GET | `/gateway/state` | Gateway view: backends and their health, connection pool, cache statistics. |
| GET | `/gateway/warehouses` | Warehouses, their database and their instances (`UP`, `STARTING`, `DOWN`, `DRAINING`). |
| PUT | `/gateway/warehouses/{name}` | Body `{"replicas": n}`: create the warehouse or scale it to n instances. `202`. |
| DELETE | `/gateway/warehouses/{name}` | Remove a warehouse the gateway created. `202`. |

All `/api/**` and `/whoami` calls take an optional `X-Warehouse` header (default `warehouse-1`).
Unknown warehouse: `404`; warehouse with no healthy instance: `503`.

### JSON or XML

The client chooses the format with the `Accept` header; errors follow the same choice.

```
curl -H "Accept: application/xml"  localhost:8080/api/games
curl -H "Accept: application/json" localhost:8080/api/games
```

### Game body (POST / PUT)

```json
{
  "title": "The Witcher 3",
  "developer": "CD Projekt Red",
  "genre": "RPG",
  "releaseYear": 2015
}
```

| Field | Rules |
|-------|-------|
| `title` | required, max 200 characters |
| `developer` | required, max 120 characters |
| `genre` | required, one of `ACTION`, `ADVENTURE`, `RPG`, `STRATEGY`, `SPORTS`, `SIMULATION` |
| `releaseYear` | between 1958 and 2100 |

A game response has the same fields plus an `id`.

### Examples

```
# create
curl -X POST localhost:8080/api/games -H "Content-Type: application/json" \
  -d '{"title":"Doom","developer":"id Software","genre":"ACTION","releaseYear":1993}'

# list RPGs, 5 per page
curl "localhost:8080/api/games?genre=RPG&size=5"

# get / delete
curl localhost:8080/api/games/<id>
curl -X DELETE localhost:8080/api/games/<id>
```

### Errors

Errors use the standard problem-details JSON format (`title`, `status`, `detail`).

| Status | When |
|--------|------|
| 400 | Malformed JSON, or a field breaks the rules above |
| 404 | Game id not found (or already deleted) |
| 409 | A game with the same title and developer already exists |

## Configuration

Defaults are in `src/main/resources/application.properties`. Docker Compose overrides the
connection settings with environment variables (`SPRING_MONGODB_URI`, `SPRING_DATA_REDIS_HOST`,
`APP_INSTANCE_ID`, `APP_WAREHOUSE`).

`APP_WAREHOUSE` picks the database: `spring.mongodb.database=gamevault_${app.warehouse}` overrides
any database in the URI, so all instances share one replica-set URI. It also prefixes the API's Redis
cache keys (`gamevault:<warehouse>:v1:`), because the warehouses share one Redis.
