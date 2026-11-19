# System Architecture

This document describes the system as it is today and the target architecture after the tasks in [TASKS.md](TASKS.md).

## 1. Current state

```
 Browser ──► Frontend (Vite + React, :5173)
                │  dev proxy (/api, /whoami, /actuator)
                ▼
            API (Spring Boot, :8080) ──► MongoDB (:27017)   games
                │
                └────────────────────► Redis   (:6379)      cache
```

| Component | Technology | Role |
|-----------|-----------|------|
| Frontend | Vite, React | One page to test every endpoint. |
| API | Spring Boot 4, Java 25 | REST API for the game catalog (`/api/games`, `/whoami`, `/actuator/health`). |
| MongoDB | `mongo:8` | Stores games. Deletes are soft (a `deletedAt` field). |
| Redis | `redis:7` | Caches single-game reads (`games` cache, 10 min TTL). Update and delete evict the entry. |

Everything starts with `docker compose up --build`.

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
        └───────┬──────────┬───────────┘
                │          │          │
                ▼          ▼          ▼
             API-1       API-2       API-3      (identical, stateless)
                │          │          │
                └──────────┼──────────┘
                           │                     ┌──────────┐
                  ┌────────┴────────┐            │  Redis   │ cache
                  ▼        ▼        ▼  ◄─────────┴──────────┘
              Mongo-1   Mongo-2   Mongo-3
              PRIMARY  SECONDARY SECONDARY      (replica set rs0)
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

**MongoDB replica set.** Three nodes hold the same data. The primary takes writes and the secondaries copy them. If the primary dies, the remaining nodes vote and one becomes the new primary in a few seconds. The API connects with a multi-host connection string, so the driver follows the change automatically.

**Redis.** One node, used only as a cache. If it is lost the system still works from MongoDB, only slower.

### Why a gateway for priority and circuit breaking
- The API instances cannot see each other's load, so only a component in front of them can order requests globally.
- A client cannot know whether a silent API will ever answer. The gateway can: it holds the journal of what it sent, gives up after a timeout and re-sends elsewhere.

## 3. Failure behavior

| Failure | What happens |
|---------|--------------|
| One API instance stops | Its circuit opens; the gateway re-sends in-flight requests to other instances. Clients see no error. |
| An API instance hangs (no answer) | Timeout, then same as above. |
| Mongo primary stops | Short pause (election, about 10 s); then writes continue on the new primary. |
| One Mongo secondary stops | No visible effect; the replica set still has a majority (2 of 3). |
| Redis stops | Reads go to MongoDB; slower, no data loss. |
| Gateway stops | System unavailable (single point of failure). In Kubernetes it can run with more than one replica. |

## 4. Deployment

- **Docker Compose** (development): all components as containers in one network, started with one command.
- **Kubernetes** (bonus): one Deployment per stateless service (frontend, gateway, API, Redis) and a StatefulSet with persistent volumes for the three Mongo nodes. Probes use `/actuator/health`. Details in Task 6 of [TASKS.md](TASKS.md).

## 5. Data and API formats

- Games: `{ id, title, developer, genre, releaseYear }`. Genres: `ACTION, ADVENTURE, RPG, STRATEGY, SPORTS, SIMULATION`.
- Responses can be JSON or XML, chosen by the client (Task 1).
- Endpoints are listed in [README.md](README.md).
