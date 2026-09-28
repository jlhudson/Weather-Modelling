# Gully

South Australia's weather from the Bureau's stations, each with the ground it speaks for.

Gully was started over on 21 September 2026 ([docs/02-decisions.md](docs/02-decisions.md), W-1). The
name is the Adelaide Hills gully wind. The values are the values: no confidence scores, no estimate
branching; a missing value is missing, never zero; every answer carries its source and its time.

**How it works, in one paragraph.** The Bureau's South Australian station file is read every ten
minutes and every reading in it is stored: where each station is, how high it is, and what it said.
Each station carries a *reach* — a polygon drawn once from the terrain around it, out to a distance,
shortened where the ground rises or falls away from the station's own height (climbing costs more
than descending, and a barrier has to hold for 3 km), ended at the sea —
which is the ground the station speaks for; reaches overlap, and a point inside several is answered
from all of them. The terrain a reach is drawn from is the open Terrain Tiles on AWS, sampled once;
Open-Meteo and Google Weather stand behind the stations for the forecast, Open-Meteo first and
Google when Open-Meteo is out of allowance. Two timers and nothing else on a clock: the file every
ten minutes, and a daily housekeeping at 9:30 that folds the stored readings into the six-hourly
history and the Bureau days, prunes, samples the terrain of any station lacking it and fills the
year of any station missing days. Everything else - a reading at a point, a point of ours, a
drought - is on demand, so the service idles until it is asked.

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
| `GET /api/v1/stations/{id}` | One station with its last readings, its terrain and reach, its drought and the record behind it. |
| `GET /api/v1/reach.geojson` | Every station's reach under the rule in force, as polygons. |
| `GET /api/v1/reading?lat=&lon=` | The weather now and the drought at a point, blended from the stations in reach or from a point of ours, and the FFDI; every value names its stations. `&force=true` asks the upstreams first - the Bureau's file now, the days the stations in reach are missing, a point of ours' current again - and `grabbed` says what came. |
| `GET /api/v1/stations/at?lat=&lon=` | The stations that speak for a point: those whose reach contains it, and the nearest three that do not, with why. |
| `GET /api/diagnostics`, `/logs`, `/logs/{id}`, the two `DELETE`s | The shape The Hub's morning agent reads. |

## Running it

**With compose.** Once per machine, create the network every stack's app joins. Then copy
`.env.development.example` to `.env`, which is set for the development profile and runs as copied, and start
the stack; `compose.override.yaml` builds the image from this checkout:

```bash
docker network create apps
docker compose up -d --build
```

The console is at <http://localhost:8082/console/map>, username `operator`, the 8-digit code from
`.env`. The stack is standalone: the service and, under the `owndb` profile `.env.development.example` turns on, its
own PostGIS and nightly backup. Two Spring profiles: `development` on your own machine, `production` on a
server (JSON logs, and the service refuses to start on a default code or password). The Hub reaches it
at `http://weather-app:8082` on the `apps` network when the two stacks share a server (its
`HUB_WEATHER_URL` default), or at `http://vps-1:8082` over Tailscale from the other, with the pre-shared
key `WEATHER_KEY_HUB` here and `HUB_WEATHER_API_KEY` there. On the server the same `compose.yaml` is
the `weather` Portainer stack from `main`, pulling the image CI pushes and using the server's shared
PostGIS; Watchtower replaces the container on every merge, and the edge stack carries the public traffic
([The Hub's Start here page](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/README.md)).

**From the IDE.** `au.gully.Application`, with Postgres reachable at `localhost:5435` (this repository's
compose Postgres, started alone with `docker compose up -d db`). Flyway creates the schema on first boot.

**The build.** `./mvnw -B -ntp verify`. The unit tests need nothing; the one end-to-end test starts a
throwaway Postgres with Testcontainers and is skipped where Docker is not available.

## Configuring it

Every setting carries its default in `GullyProperties` and is printed once at startup; the shipped
`application.yml` is the handful that differ between machines, each from an environment variable, all
in `.env.development.example`; `.env.production.example` is the same set of names for a server.

## The docs

- [docs/01-what-it-is.md](docs/01-what-it-is.md) — the stations, the reach, the upstreams, the console
- [docs/02-decisions.md](docs/02-decisions.md) — the decisions, one W-number each
- [docs/03-fire-danger.md](docs/03-fire-danger.md) — every fire danger figure: what it rests on, how it was checked, what it cannot tell you
- [docs/04-sources-and-findings.md](docs/04-sources-and-findings.md) — every outside source: what we take, when, what it costs, and what we found building on it
