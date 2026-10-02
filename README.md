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
Port **8082**, inside the container and on your PC.

## The API

Every `/api/**` route needs an API key (`X-Api-Key` or `Authorization: Bearer`), issued on
`/console/api-keys` with a scope. Answers are JSON, times ISO-8601 UTC, errors RFC 9457 problem details,
bodies compressed and fingerprinted (a weak `ETag` that only changes when the answer does). A valid
key is never rate-limited.

| Route | What it answers |
|---|---|
| `GET /api/v1/stations.geojson` | Every station as a point, with its latest values and how old they are. |
| `GET /api/v1/stations/{id}` | One station with its last readings, its terrain and reach, its drought and the record behind it. |
| `GET /api/v1/reach.geojson` | Every station's reach under the rule in force, as polygons. |
| `GET /api/v1/reading?lat=&lon=` | The weather now and the drought at a point, blended from the stations in reach or from a point of ours, and the FFDI; every value names its stations. `&force=true` asks the upstreams first - the Bureau's file now, the days the stations in reach are missing, a point of ours' current again - and `grabbed` says what came. |
| `GET /api/v1/stations/at?lat=&lon=` | The stations that speak for a point: those whose reach contains it, and the nearest three that do not, with why. |
| `GET /api/v1/warnings`, `/api/v1/warnings.geojson` | The Bureau's warnings in force in South Australia and Tasmania, each with the areas it covers; as GeoJSON, the same warnings each with its shape - the union of its areas' forecast districts, fire weather districts or marine zones, from the Bureau's own shapefiles - or a null geometry where none of its areas has one (W-50). |
| `GET /api/v1/fire-danger?from=&to=` | The CFS's published rating, Fire Behaviour Index and total fire ban for every fire ban district on each day (today when absent, at most 93 days), kept for ever (W-45); `published` says the day was read as the day, not only forecast. |
| `GET /api/diagnostics`, `/logs`, `/logs/{id}`, the two `DELETE`s | The shape The Hub's morning agent reads. |

## Running it

On your PC, run `compose.development.yaml` in IntelliJ (the ▶ beside `services:`, or **Weather
(development)**): its database, Weather built from this checkout, a nightly backup, and the
weather.surefirehudson.com tunnel. The console is at <http://localhost:8082/console/map>, username
`operator`, code `12345678`. Its settings are
`.env.development`, which The-Hub-Database's `env/make-env.sh` writes. A server runs
`compose.production.yaml` through Portainer with `.env.production`
([Development](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md) ·
[Production](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md)).

Two Spring profiles: `development` on your PC, `production` on a server (JSON logs, and the service
refuses to start on a default code or password). The Hub reaches it with the pre-shared key
`WEATHER_KEY_HUB` here and `HUB_WEATHER_API_KEY` there. The one call the other way is the console's Feedback
page, which hands a message to the Hub's `/api/feedback` at `WEATHER_HUB_URL` with `WEATHER_HUB_API_KEY`;
either blank, the page says it cannot send and the message is logged instead.

**From the IDE.** Stop the `weather-app` container and run `au.gully.Application`; it finds the
database on `localhost:5434`. Flyway creates the schema on first boot.

**The build.** `./mvnw -B -ntp verify`. The unit tests need nothing; the one end-to-end test starts a
throwaway Postgres with Testcontainers and is skipped where Docker is not available.

## Configuring it

Every setting carries its default in `GullyProperties` and is printed once at startup; the shipped
`application.yaml` is the handful that differ between machines, each from an environment variable
that `env/make-env.sh` writes into `.env.development` and `.env.production`.

## The docs

- [docs/01-what-it-is.md](docs/01-what-it-is.md) — the stations, the reach, the upstreams, the console
- [docs/02-decisions.md](docs/02-decisions.md) — the decisions, one W-number each
- [docs/03-fire-danger.md](docs/03-fire-danger.md) — every fire danger figure: what it rests on, how it was checked, what it cannot tell you
- [docs/04-sources-and-findings.md](docs/04-sources-and-findings.md) — every outside source: what we take, when, what it costs, and what we found building on it
