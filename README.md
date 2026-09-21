# Gully

South Australia's weather from the Bureau's stations, each with the ground it speaks for.

Gully was started over on 21 September 2026 ([docs/02-decisions.md](docs/02-decisions.md), W-1). The
name is the Adelaide Hills gully wind. The values are the values: no confidence scores, no estimate
branching; a missing value is missing, never zero; every answer carries its source and its time.

**How it works, in one paragraph.** The Bureau's South Australian station file is read every ten
minutes and every station in it is held: where it is, how high it is, and what it last said. Each
station will carry a *reach* — a polygon drawn once from the terrain around it, out to a distance,
shortened where the ground rises or falls away from the station's own height, ended at the sea —
which is the ground the station speaks for; reaches overlap, and a point inside several is answered
from all of them. The terrain a reach is drawn from is the open Terrain Tiles on AWS, sampled once;
Open-Meteo and Google Weather stand behind the stations for the forecast, Open-Meteo first and
Google when Open-Meteo is out of allowance. One in-memory register holds all of it, rebuilt from Postgres at
start; the database is not touched to answer a request.

Java 25, Spring Boot 4.1.1, PostgreSQL 18, one Maven module, plain SQL (no entity manager), Flyway.
Port **8082** inside the container; `WEATHER_PORT` is the host side of the compose mapping.

## The API

Every `/api/**` route needs an API key (`X-Api-Key` or `Authorization: Bearer`), issued on
`/console/api-keys` with a scope. Answers are JSON, times ISO-8601 UTC, errors RFC 9457 problem details,
bodies compressed and fingerprinted (a weak `ETag` that only changes when the answer does), rate
limits in the `RateLimit-*` headers.

| Route | What it answers |
|---|---|
| `GET /api/v1/stations.geojson` | Every station as a point, with its latest values and how old they are. |
| `GET /api/v1/stations/{id}` | One station with its last readings, its terrain and its reach. |
| `GET /api/v1/reach.geojson` | Every station's reach under the rule in force, as polygons. |
| `GET /api/diagnostics`, `/logs`, `/logs/{id}`, the two `DELETE`s | The shape The Hub's morning agent reads. |

## Running it

**With compose.** Copy `.env.example` to `.env`, set `WEATHER_CONSOLE_CODE` and `POSTGRES_PASSWORD`, then:

```bash
docker compose up -d --build
```

The console is at <http://localhost:8082/console/map>, username `operator`, the 8-digit code from
`.env`. The stack is Postgres, the service, and with `COMPOSE_PROFILES=edge` and a
`CLOUDFLARE_TUNNEL_TOKEN` the Cloudflare connector that publishes it. The Hub reaches it at
`HUB_WEATHER_URL` with a key issued on `/console/api-keys` here.

**From the IDE.** `au.gully.Application`, with Postgres reachable at `localhost:5435` (this repository's
compose Postgres, started alone with `docker compose up -d db`). Flyway creates the schema on first boot.

**The build.** `./mvnw -B -ntp verify`. The unit tests need nothing; the one end-to-end test starts a
throwaway Postgres with Testcontainers and is skipped where Docker is not available.

## Configuring it

Every setting carries its default in `GullyProperties` and is printed once at startup; the shipped
`application.yml` is the handful that differ between machines, each from an environment variable, all
in `.env.example`.

## The docs

- [docs/01-what-it-is.md](docs/01-what-it-is.md) — the stations, the reach, the upstreams, the console
- [docs/02-decisions.md](docs/02-decisions.md) — the decisions, one W-number each
