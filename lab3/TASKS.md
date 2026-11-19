# Bonus Requirements and Implementation Plan

## Requirements (translated from Romanian)

| # | Requirement |
|---|-------------|
| 8 | **BONUS – Docker + Kubernetes.** Run the system on Kubernetes. |
| 9 | **BONUS – JSON/XML switch.** A flag sent by the client lets the user choose between JSON and XML, using an option or button in the UI. The client picks the format and the server changes its behavior accordingly. |
| 10 | **BONUS – Request priority.** A request can be given a priority so that it is processed first. |
| 11 | **BONUS – Circuit breaker.** If a request is sent and the API does not answer, we cannot know whether an answer will ever arrive. So we cut the path to that API and redirect traffic to another API instance that does answer. Because of this, the **gateway must remember what it sent**. |
| 12 | **BONUS – Replication.** Each database comes with 2 more databases connected to it. If the original (primary) database dies, a new master is elected from the 2 remaining ones. |

Requirements 1–7 were not provided, so they are not covered here.

## Task list (easiest to hardest)

Every task ends with its tests. A task is done only when its tests pass.

### Task 1 – JSON / XML switch (req. 9)  ·  Easy
`jackson-dataformat-xml` is already in the `pom.xml`, so Spring can already return XML. We only need to expose and use it.

- Backend: confirm content negotiation works with the `Accept` header (`application/json` / `application/xml`). Optionally also accept a `?format=xml` query parameter.
- Make sure the `Page` response and the error responses (problem details) also serialize to XML.
- Frontend: add a JSON/XML toggle at the top of the page. It sets the `Accept` header (and the `Content-Type` of request bodies) for all calls and shows the raw response.
- **Tests**
  - MockMvc: `GET /api/games` with `Accept: application/xml` returns XML; with `application/json` returns JSON.
  - MockMvc: `POST` with an XML body creates a game.
  - Error case returns XML when XML is requested.
  - Manual: toggle in the UI and compare responses.

### Task 2 – Gateway skeleton and multiple API replicas  ·  Medium  (prerequisite for 10 and 11)  ·  ✅ DONE
Implemented in [gateway/](gateway/README.md): keep-alive connection pool, Redis response cache
(JSON/XML keyed separately, generation-based invalidation, request coalescing), round-robin or
least-connections balancing, health checks and safe failover. Three API replicas in Docker Compose.

Requirements 10 and 11 talk about a gateway, which does not exist yet.

- New module `gateway/` (Spring Boot, same Java version). It exposes the same `/api/**` paths and forwards them to the API instances.
- Run 2–3 API replicas in Docker Compose (`api-1`, `api-2`, `api-3`), each with its own `APP_INSTANCE_ID`.
- Gateway load-balances round-robin and passes through the `X-Instance-Id` header, so the UI shows which instance answered.
- The frontend now talks only to the gateway.
- **Tests**
  - Unit: round-robin picks instances in order and wraps around.
  - Integration (gateway with a stub backend): request is forwarded with the same method, path, query, headers and body.
  - Manual: call `/whoami` several times and see the instance id change.

### Task 3 – Request priority (req. 10)  ·  Medium
- Client sends a priority, for example the header `X-Priority: HIGH | NORMAL | LOW` (default `NORMAL`). The UI gets a priority dropdown.
- The gateway puts incoming requests in a `PriorityBlockingQueue` ordered by priority, then by arrival time. A fixed pool of worker threads takes requests from the queue and forwards them. Priority only has an effect when the workers are busy, so the pool size must be limited.
- Prevent starvation: a low-priority request waiting longer than N seconds is promoted.
- **Tests**
  - Unit: queue returns HIGH before NORMAL before LOW, and FIFO inside the same priority.
  - Unit: aged LOW request gets promoted.
  - Integration: with 1 worker and a slow backend, send LOW, LOW, HIGH; the HIGH one finishes before the second LOW.
  - Invalid priority value returns 400.

### Task 4 – Circuit breaker and request journal (req. 11)  ·  Hard
- Per API instance, the gateway keeps a circuit breaker with states `CLOSED → OPEN → HALF_OPEN`. Use Resilience4j.
  - A call that times out or fails counts as a failure.
  - After N failures the circuit opens and that instance gets no traffic.
  - After a wait time, one trial request is allowed (`HALF_OPEN`). If it succeeds, the circuit closes again.
- **Request journal:** for every request the gateway stores `requestId`, method, path, body, target instance, start time and status (`SENT`, `DONE`, `FAILED`, `RETRIED`). This is what the requirement means by "the gateway must remember what it sent".
- When an instance times out, the gateway re-sends the journaled request to another healthy instance.
  - `GET`, `PUT`, `DELETE` are safe to retry (same result if repeated).
  - `POST` is risky because the first attempt may have succeeded. The API already rejects duplicate title+developer with `409`, so a retry after a possible success returns `409`; the gateway treats that as "already created" and reads the game back. Document this rule.
- Expose `GET /gateway/state` (circuit state per instance and recent journal entries) and show it in the UI.
- **Tests**
  - Unit: circuit opens after N failures, half-opens after the wait time, closes after a success.
  - Integration: stop one API container; requests still succeed through another instance.
  - Integration: a backend that hangs causes a timeout, the journal records `SENT → RETRIED → DONE`.
  - Integration: `POST` retry after a lost response does not create two games.
  - Manual: `docker compose stop api-1`, watch `/gateway/state`, start it again and see it recover.

### Task 5 – MongoDB replication (req. 12)  ·  Hard
- Replace the single `mongo` service with a 3-node replica set (`mongo-1`, `mongo-2`, `mongo-3`) in Docker Compose. One node is primary, the others are secondaries. Mongo elects a new primary automatically when the primary dies.
- An init step (one-shot container) runs `rs.initiate(...)` with the three members.
- Change the connection string to `mongodb://mongo-1:27017,mongo-2:27017,mongo-3:27017/gamevault?replicaSet=rs0` so the driver finds the new primary after a failover.
- Redis stays a single node. It is only a cache, so losing it costs speed, not data.
- **Tests**
  - Manual: `rs.status()` shows 1 primary and 2 secondaries.
  - Manual: write a game, stop the primary container, wait for the election (about 10 s), then read the game and write another one.
  - Automated (Testcontainers `MongoDBContainer` replica set, or a script): kill the primary and check that writes succeed again after the election.
  - Check that `/actuator/health` stays `UP` after failover.

### Task 6 – Kubernetes (req. 8)  ·  Hardest (do last)
It packages everything built before, so it needs all other tasks to be stable.

- Use a local cluster (kind or minikube, or Docker Desktop's Kubernetes).
- Manifests in `k8s/`:
  - `api` Deployment (3 replicas) and Service.
  - `gateway` Deployment and Service (exposed with NodePort or Ingress).
  - `frontend` Deployment and Service.
  - `mongo` StatefulSet (3 pods) with a headless Service and persistent volumes. This is the Kubernetes way to run a replica set.
  - `redis` Deployment and Service.
  - ConfigMap for settings; readiness and liveness probes use `/actuator/health`.
- Images need to be built and loaded into the cluster (or pushed to a registry).
- **Tests**
  - `kubectl get pods` shows everything `Running` and `Ready`.
  - Delete an `api` pod: Kubernetes recreates it and the gateway keeps answering.
  - Delete the Mongo primary pod: a new primary is elected and writes work again.
  - Scale `api` to 5 replicas and see new instance ids in the UI.

## Suggested order and checkpoints

| Step | Task | Checkpoint |
|------|------|------------|
| 1 | Task 1 | UI toggles JSON/XML |
| 2 | Task 2 | UI works through the gateway, traffic is spread across replicas |
| 3 | Task 3 | High-priority request overtakes queued low-priority ones |
| 4 | Task 4 | Killing an API container causes no failed requests |
| 5 | Task 5 | Killing the Mongo primary causes only a short pause |
| 6 | Task 6 | The same behavior inside Kubernetes |

## Decisions (confirmed)
- The gateway is written in Java/Spring Boot, like the rest of the project.
- "Priority" is chosen by the user in the frontend and sent as a header.
- Retrying `POST` relies on the existing duplicate check (409) instead of idempotency keys.

## Course context
This is a Distributed Programming course. The main goal of this lab is to keep the databases in sync (Task 5), and concurrency and thread safety are graded. Every task that adds concurrent code must also update [CONCURRENCY.md](CONCURRENCY.md) and include a concurrency test (many threads, no lost or duplicated data). The gaps found in the existing code (lost updates on `PUT`, cache stampede, create race returning 500) are already fixed; the remaining known limit is listed there.
