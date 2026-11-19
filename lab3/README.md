# Lab 3 – Game Catalog API

Spring Boot 4 (Java 25) REST API for a catalog of video games.
Games are stored in **MongoDB** and single-game reads are cached in **Redis**.
Clients reach the API through a **gateway** (smart proxy) that load-balances over
three API instances and caches responses. See [gateway/README.md](gateway/README.md).

```
client ──► gateway :8080 ──► api-1 ┐
           (Redis cache)  ├─► api-2 ├──► MongoDB (one shared instance)
                          └─► api-3 ┘
```

## Run

```
docker compose up --build
```

Starts `gateway` (port 8080), `api-1`, `api-2`, `api-3` (not published; reachable only
through the gateway), `web` (5173), `mongo` (27017) and `redis` (6379).
Stop with `docker compose down` (add `-v` to also delete the Mongo data).

Every response includes an `X-Instance-Id` header that shows which API instance answered,
and an `X-Cache` header (`HIT`, `MISS`, `COALESCED`, `BYPASS`) set by the gateway.

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
`APP_INSTANCE_ID`).
