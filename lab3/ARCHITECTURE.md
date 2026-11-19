# System Architecture

This document describes the system as it is today and the target architecture after the tasks in [TASKS.md](TASKS.md).

## 1. Current state

```
 Browser ──► Frontend (Vite + React, :5173)
                │  dev proxy (/api, /whoami, /actuator, /gateway)
                ▼
            Gateway (Spring Boot, :8080) ── response cache ──► Redis (:6379)
                │  routes by X-Warehouse; pooled keep-alive connections, round-robin, health checks, failover
                │  creates per warehouse: API containers + a MongoDB replica set (Docker socket)
                ├──► warehouse-1: api-warehouse-1-1 .. -n ──► rs-warehouse-1 (max(3, n) mongo nodes)
                ├──► warehouse-2: ...                     ──► rs-warehouse-2
                └──► warehouse-N: ...                     ──► rs-warehouse-N     every API ──► Redis (single-game cache)
```

| Component | Technology | Role |
|-----------|-----------|------|
| Frontend | Vite, React | One page to test every endpoint. |
| Gateway | Spring Boot 4, Apache HttpClient 5 | Smart proxy: keep-alive connection pool, Redis response cache, load balancing with health checks and failover. See [gateway/README.md](gateway/README.md). |
| API | Spring Boot 4, Java 25 | REST API for the game catalog (`/api/games`, `/whoami`, `/actuator/health`). |
| MongoDB | `mongo:8` | Stores games, one replica set per warehouse (database `gamevault_<warehouse>`). Deletes are soft (a `deletedAt` field). |
| Redis | `redis:7` | Caches single-game reads (`games` cache, 10 min TTL). Update and delete evict the entry. |

Everything starts with `docker compose up --build`: compose starts the gateway, Redis and the frontend; the gateway creates the warehouses.

### Request flow inside the API
1. `InstanceIdFilter` adds the `X-Instance-Id` header to every response.
2. `GameController` validates the input and calls `GameService`.
3. `GameService.findById` checks Redis first; on a miss it reads MongoDB and stores the result.
4. `ErrorHandler` turns exceptions into problem-details responses (400, 404, 409).

## 2. Target architecture

```
                    Browser
                       │
                       ▼
              Frontend (React)
              JSON/XML toggle, priority selector, gateway state view
                       │  HTTP
                       ▼
        ┌──────────────────────────────┐
        │           Gateway            │
        │  1. Priority queue           │
        │  2. Worker pool              │
        │  3. Load balancer            │
        │  4. Circuit breaker / inst.  │
        │  5. Request journal          │
        └───────┬──────────────────────┘
                │ X-Warehouse picks the warehouse
        ┌───────┴─────────────────────────┐
        ▼                                 ▼
     warehouse-1                       warehouse-N        (created on request)
     api-warehouse-1-1 .. -n           api-warehouse-N-1 .. -n
        │ API i reads node i,             │                 ┌──────────┐
        │ writes go to the primary        │                 │  Redis   │ cache
        ▼                                 ▼                 └──────────┘
     rs-warehouse-1                    rs-warehouse-N
     mongo-warehouse-1-1 .. -m         mongo-warehouse-N-1 .. -m
     1 PRIMARY + SECONDARIES           m = max(3, n)
```

### Components

**Frontend.** Sends all calls to the gateway only. It lets the user pick JSON or XML (sets `Accept` / `Content-Type`), pick a request priority (`X-Priority`), and see the gateway state (circuits and recent requests).

**Gateway (new).** The single entry point. For each incoming request it does the following, in order:
1. **Journal:** assigns a `requestId` and records the request (method, path, body, time).
2. **Priority queue:** places the request in a queue ordered by priority, then arrival time. Old low-priority requests get promoted so they never starve.
3. **Worker pool:** a limited number of workers take requests from the queue. This limit is what makes priority meaningful.
4. **Load balancing:** a worker picks the next API instance whose circuit is not open (round-robin).
5. **Circuit breaker:** one breaker per API instance. Timeouts and errors open the circuit and stop traffic to that instance; after a wait, a trial request tests it again.
6. **Failover:** if the call fails or times out, the journal entry is used to re-send the request to another healthy instance. The journal is updated with the result.

**API instances.** The existing Spring Boot application, run as several identical copies. They keep no local state, so any instance can serve any request. Each has its own `APP_INSTANCE_ID`, returned in `X-Instance-Id`.

**Warehouses (implemented).** The instances are grouped into warehouses. A warehouse has 1..5 API instances (the client chooses) and its own MongoDB replica set `rs-<warehouse>` with as many database copies as API instances, but at least 3. The database is `gamevault_<warehouse>` (set by `APP_WAREHOUSE`). API instance i reads from copy i; writes go to the primary. The gateway routes by the `X-Warehouse` header and balances and fails over only inside one warehouse. The client scales a warehouse, or creates a new one, at runtime (`PUT /gateway/warehouses/{name}` or the UI); the gateway starts and stops both the API containers and the Mongo nodes through the Docker socket, in the background (desired state + one reconcile thread per warehouse). In Kubernetes (Task 6) the same `InstanceProvisioner` interface would create a Deployment and a StatefulSet per warehouse instead.

**MongoDB replica sets (one per warehouse).** All nodes of a warehouse hold the same data. The primary takes writes and the secondaries copy them. If the primary dies, the remaining nodes vote and one becomes the new primary in a few seconds. That needs a majority alive, so a warehouse never has fewer than 3 copies (3 survive 1 failure, 5 survive 2). Each API instance connects with all of its warehouse's nodes, so the driver follows the change automatically.

**Redis.** One node, used only as a cache. If it is lost the system still works from MongoDB, only slower.

### Why a gateway for priority and circuit breaking
- The API instances cannot see each other's load, so only a component in front of them can order requests globally.
- A client cannot know whether a silent API will ever answer. The gateway can: it holds the journal of what it sent, gives up after a timeout and re-sends elsewhere.

## 3. Failure behavior

| Failure | What happens |
|---------|--------------|
| One API instance stops | Its circuit opens; the gateway re-sends in-flight requests to other instances. Clients see no error. |
| An API instance hangs (no answer) | Timeout, then same as above. |
| A warehouse's Mongo primary stops | That warehouse pauses (election, about 10 s; requests may get 503); then writes continue on the new primary. Other warehouses are not affected. |
| One Mongo secondary stops | No visible effect while a majority of the warehouse's copies is alive. Its API instance reads from another copy. |
| Redis stops | Reads go to MongoDB; slower, no data loss. |
| Gateway stops | System unavailable (single point of failure). In Kubernetes it can run with more than one replica. |

## 4. Deployment

- **Docker Compose** (development): the gateway, Redis and the frontend, started with one command. The gateway creates the warehouses' API containers and Mongo nodes itself, on the same network.
- **Kubernetes** (bonus): one Deployment per stateless service (frontend, gateway, Redis), and per warehouse an API Deployment and a StatefulSet with persistent volumes for its Mongo nodes. Probes use `/actuator/health`. Details in Task 6 of [TASKS.md](TASKS.md).

## 5. Data and API formats

- Games: `{ id, title, developer, genre, releaseYear }`. Genres: `ACTION, ADVENTURE, RPG, STRATEGY, SPORTS, SIMULATION`.
- Responses can be JSON or XML, chosen by the client (Task 1).
- Endpoints are listed in [README.md](README.md).
