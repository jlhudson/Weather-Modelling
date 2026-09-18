# Gully

The weather, and the fire danger, at a point — now, or at a time.

Gully is the overhaul of the Weather service that was split out of [The Hub Database](https://github.com/jlhudson/The-Hub-Database)
(its D-242, D-249 and D-252), rebuilt to the catalogue in [docs/06-overhaul.md](docs/06-overhaul.md) on
19 September 2026, every item. The name is the Adelaide Hills gully wind. The Hub asks, Gully answers,
and the values are the values: no confidence scores, no "estimate" or "actual" branching; a missing
value is missing, never zero; every answer carries its source and its time.

**How it works, in one paragraph.** Australia is divided into 15 km hexagons by arithmetic (the two
constants are at the top of `Grid`); a point is answered by the reading held for its hexagon, fetched
once at the centre from Open-Meteo — Google Weather is the overflow — and kept until the upstream says
it is stale. Nothing is pre-warmed: a hexagon exists once something inside it has been asked about,
and The Hub's sweep of its open incidents keeps their hexagons warm. Every Bureau of Meteorology station
gets a hexagon of its own whose "now" is the station's values, free, every ten minutes; the Bureau's
warnings, the CFS's district rating and total fire ban, the grass curing the operator enters, the land
use and elevation from mounted terrain files, and a drought index stepped forward daily from the
station ledger all sit on the hexagon, so the full fire picture — McArthur forest and grassland, the
AFDRS grassland index and its rating, the official rating, the wind and its next change, the warnings
— is a lookup, never a computation on request. History is written only while an incident is present.
One in-memory cache holds all of it, rebuilt from Postgres at start; the database is not touched to
answer a request.

Java 25, Spring Boot 4.1.1, PostgreSQL 18, one Maven module, plain SQL (no entity manager), Flyway.
Port **8082** inside the container; `WEATHER_PORT` is the host side of the compose mapping.

## The API

Every `/api/**` route needs an API key (`X-Api-Key` or `Authorization: Bearer`), issued on
`/console/api-keys` with a scope. Answers are JSON, times ISO-8601 UTC, errors RFC 9457 problem details,
bodies compressed and fingerprinted (a strong `ETag` that only changes when the reading does), rate
limits in the `RateLimit-*` headers. The OpenAPI document is at `/api/v1/openapi.json`.

| Route | What it answers |
|---|---|
| `GET /api/v1/readings?lat=&lon=&forecast=&at=&incident=` | The reading at a point: the hexagon, the source, the conditions now (station or model), the nearest station's values, the fire picture, flood, drought, warnings, and with `forecast=true` the days and hours. `at=` answers from history; `incident=` writes it. |
| `GET /api/v1/hexagons.geojson?at=` | Every hexagon held, as polygons carrying the values a map colours by. Pre-rendered, fingerprinted, never fetches. |
| `GET /api/v1/hexagons`, `GET /api/v1/hexagons/{id}` | The list, and everything held for one: reading, drought state, river, history. |
| `GET /api/v1/fire-indices?temperatureC=&humidityPct=&windKmh=&droughtFactor=&curingPct=&fuelLoadTHa=&condition=` | The indices for given inputs, from the one set of formulas. |
| `GET /api/v1/status`, `GET /api/v1/upstreams/{id}/spend?since=`, `/spend/daily?from=&to=`, `/spend/hourly` | Upstreams with their allowance and breaker, sources, what is held, and the spend ledger. |
| `GET /api/v1/contract/reading.schema.json` | The reading's shape as a JSON Schema. Public. |
| `GET /api/diagnostics`, `/logs`, `/logs/{id}`, the two `DELETE`s | The shape The Hub's morning agent reads. |
| `GET /api/weather` | The old route in its old shape, for one release. |

Full shapes and curl examples: [docs/02-api.md](docs/02-api.md). The contract itself:
[src/main/resources/contract/reading.schema.json](src/main/resources/contract/reading.schema.json),
kept identically in The Hub and checked by both builds.

## Running it

**With compose.** Copy `.env.example` to `.env`, set `WEATHER_CONSOLE_CODE` and `POSTGRES_PASSWORD`, then:

```bash
docker compose up -d --build
```

The console is at <http://localhost:8082/console/map>, username `operator`, the 8-digit code from
`.env`. The stack is Postgres, the service, and with `COMPOSE_PROFILES=edge` and a
`CLOUDFLARE_TUNNEL_TOKEN` the Cloudflare connector that publishes it. The Hub reaches it at
`HUB_WEATHER_URL` with a key issued on `/console/api-keys` here. The database is the same volume the
old service used: the migration runs over it and keeps the keys.

**From the IDE.** `au.gully.Application`, with Postgres reachable at `localhost:5435` (this repository's
compose Postgres, started alone with `docker compose up -d db`). Flyway creates the schema on first boot.

**The build.** `./mvnw -B -ntp verify`. The unit tests need nothing; the one end-to-end test starts a
throwaway Postgres with Testcontainers and is skipped where Docker is not available.

## Configuring it

Every setting carries its default in `GullyProperties` and is printed once at startup; the shipped
`application.yml` is the handful that differ between machines, each from an environment variable, all
in `.env.example`. The full list: [docs/03-configuration.md](docs/03-configuration.md).

## The docs

- [docs/01-what-it-does.md](docs/01-what-it-does.md) — hexagons, upstreams, the Bureau, the CFS, drought, history, the fire picture
- [docs/02-api.md](docs/02-api.md) — the contract, with curl
- [docs/03-configuration.md](docs/03-configuration.md) — every setting and every environment variable
- [docs/04-migration-from-the-hub.md](docs/04-migration-from-the-hub.md) — what moved, what the Hub changed, what a second copy would need
- [docs/05-decisions.md](docs/05-decisions.md) — the decisions this service took
- [docs/06-overhaul.md](docs/06-overhaul.md) — the catalogue this rebuild was made from, ticked
