# Gully

South Australia's weather from the Bureau's stations, each with the ground it speaks for.

## Start here

| I want to | Do this |
|---|---|
| Set up my PC and run Weather | [Development](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md), then **Run it** below |
| Ship a change to production | Merge to `main`. [Production → Ship a change](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#ship-a-change) says what happens and how to check it |
| Change a setting or secret | [Production → Change a setting or secret](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#change-a-setting-or-secret) |
| Put the servers up from scratch | [Lightsail, start to finish](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/lightsail.md) |
| Fix something that is broken | [Production → When something is wrong](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#when-something-is-wrong) |
| Build or change Weather with an AI agent | [CLAUDE.md](CLAUDE.md) |

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

## Run it

On your PC, with Docker Desktop running:

1. In IntelliJ's terminal, in The-Hub-Database, run make-env: `.\env\make-env.cmd`
   ([Which terminal](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md#which-terminal)). It writes this checkout's `.env.development` (and
   `.env.production`). Never edit or commit either: change `~/cranklyradix-env/secrets.env` and run it again.
   **Check:** `.env.development` is in the root of this checkout.
2. Open `compose.development.yaml` in IntelliJ and click the ▶ beside `services:`, or pick **Weather
   (development)** in the run configurations, or in a terminal here:

   ```bash
   docker compose -f compose.development.yaml up -d
   ```

   It starts four containers: `weather-db` (PostGIS, on localhost:5434, keeps its data), `weather-app`
   (built from this checkout on every run; Flyway creates the schema on first boot), `weather-backup` (a
   nightly dump into `./backups`) and `weather-tunnel` (weather.surefirehudson.com).
   **Check:** `docker compose -f compose.development.yaml ps` lists the four, `weather-db` healthy.
3. Open <http://localhost:8082/login> and sign in as `operator`, code `12345678`.
   **Check:** the console opens at <http://localhost:8082/console/map>;
   <http://localhost:8082/actuator/health/readiness> says `UP`.
4. After a code change, run step 2 again: only `weather-app` is rebuilt and replaced.
   **Check:** step 3 again.

Two Spring profiles: `development` on your PC, `production` on a server (JSON logs, and the service
refuses to start on a default code or password). A server runs `compose.production.yaml` through
Portainer with `.env.production` and the image CI built from `main` (`ghcr.io/jlhudson/weather-app:main`)
([Development](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md) ·
[Production](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md)). The Hub
reaches it with the pre-shared key `WEATHER_KEY_HUB` here and `HUB_WEATHER_API_KEY` there.

### Debug it from IntelliJ

1. Start `compose.development.yaml` once (step 2 above) for its database on localhost:5434.
2. Stop the `weather-app` container (Services → Docker → the container → Stop).
3. Run `au.gully.Application` from IntelliJ (Debug for breakpoints), or `./mvnw spring-boot:run`.
   A run from here does not read `.env.development`: on a database that has no console user yet, set
   `WEATHER_CONSOLE_CODE=12345678` in the run configuration, or the start stops with
   `WEATHER_CONSOLE_CODE must be exactly 8 digits`. Without `GOOGLE_WEATHER_KEY` the Google upstream is off.
   **Check:** <http://localhost:8082/login> answers.
4. When done, stop it and press ▶ on the compose file to put the container back.

### Build and test

1. With JDK 25:

   ```bash
   ./mvnw -B -ntp verify
   ```

   The unit tests need nothing; the one end-to-end test starts a throwaway Postgres with Testcontainers
   and is skipped where Docker is not available.
   **Check:** `BUILD SUCCESS`. This is the command CI runs on `main`.

## Configuring it

Every setting carries its default in `GullyProperties` and is printed once at startup; the shipped
`application.yaml` is the handful that differ between machines, each from an environment variable
that `env/make-env.sh` writes into `.env.development` and `.env.production`.

To change one:

1. The-Hub-Database's `env/make-env.sh` writes `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`,
   `TUNNEL_TOKEN`, `WEATHER_CONSOLE_CODE`, `WEATHER_KEY_HUB`, `GOOGLE_WEATHER_KEY` and `CARTO_API_KEY`, as
   `env.template` in this repository says. For an account key or a tunnel token, edit
   `~/cranklyradix-env/secrets.env`; for a new variable, add it to `env.template` (keys, codes and
   passwords are made by the script). Then run make-env in The-Hub-Database, as in *Run it*.
2. On your PC, press ▶ on `compose.development.yaml` again. On a server, follow
   [Production → Change a setting or secret](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#change-a-setting-or-secret).
   **Check:** <http://localhost:8082/actuator/health/readiness> says `UP`; the startup log prints every
   effective setting once. Under `production` a blank or default secret stops it at startup with the
   setting named (`ProductionGuard`).

## The docs

- [docs/01-what-it-is.md](docs/01-what-it-is.md) — the stations, the reach, the upstreams, the console
- [docs/02-decisions.md](docs/02-decisions.md) — the decisions, one W-number each
- [docs/03-fire-danger.md](docs/03-fire-danger.md) — every fire danger figure: what it rests on, how it was checked, what it cannot tell you
- [docs/04-sources-and-findings.md](docs/04-sources-and-findings.md) — every outside source: what we take, when, what it costs, and what we found building on it
