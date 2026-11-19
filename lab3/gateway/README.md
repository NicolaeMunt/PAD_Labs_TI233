# Gateway (smart proxy)

The single entry point of the system (Etapa 2 of the lab). Clients send every request to
the gateway; the gateway sends it to one of the API instances (DW nodes) and returns the
answer. It exposes the same paths as the API (`/api/**`, `/whoami`), so a client only
changes the host.

```
                      ┌──────────────── gateway ────────────────┐
client ──keep-alive──►│ cache? ─hit─► answer from Redis         │
                      │   │miss                                 │
                      │   ▼                                     │
                      │ coalesce concurrent misses              │
                      │   ▼                                     │
                      │ load balancer ─► healthy backend        │──pooled──► api-1 / api-2 / api-3
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

## Configuration

`src/main/resources/application.properties`; Docker Compose overrides with environment variables.

| Property | Env variable | Default |
|----------|--------------|---------|
| `gateway.backends` | `GATEWAY_BACKENDS` | `http://localhost:8081,...8082,...8083` |
| `gateway.strategy` | `GATEWAY_STRATEGY` | `round-robin` |
| `gateway.health.interval` / `timeout` | `GATEWAY_HEALTH_INTERVAL` / `GATEWAY_HEALTH_TIMEOUT` | `2s` / `2s` |
| `gateway.health.failures-before-down` | `GATEWAY_HEALTH_FAILURESBEFOREDOWN` | `2` |
| `gateway.pool.response-timeout` | `GATEWAY_POOL_RESPONSE_TIMEOUT` | `10s` |
| `gateway.cache.enabled` / `ttl` | `GATEWAY_CACHE_ENABLED` / `GATEWAY_CACHE_TTL` | `true` / `30s` |

## Demo

```bash
docker compose up --build

# load balancing: the instance changes on every call
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -D - localhost:8080/whoami | grep X-Instance-Id; done

# caching: first MISS, then HIT (same X-Instance-Id: the one that produced the cached answer)
curl -s -D - -o /dev/null localhost:8080/api/games | grep -E "X-Cache|X-Instance"
curl -s -D - -o /dev/null localhost:8080/api/games | grep -E "X-Cache|X-Instance"
curl -s -D - -o /dev/null -H "Accept: application/xml" localhost:8080/api/games | grep X-Cache   # MISS: XML is separate

# a write invalidates the cache
curl -s -X POST localhost:8080/api/games -H "Content-Type: application/json" \
  -d '{"title":"Doom","developer":"id Software","genre":"ACTION","releaseYear":1993}'
curl -s -D - -o /dev/null localhost:8080/api/games | grep X-Cache                              # MISS

# failover: stop one instance, requests keep working on the other two
docker compose stop api-2
for i in 1 2 3 4; do curl -s -o /dev/null -w "%{http_code} " localhost:8080/whoami; done
curl -s localhost:8080/gateway/state        # api-2 healthy=false
docker compose start api-2                  # back in rotation after its health check passes
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
