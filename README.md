# Gully

South Australia's weather from the Bureau's stations, each with the ground it speaks for.

## Start here

| I want to | Do this |
|---|---|
| Set up my PC and run Weather | [Development](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md), then **Run it** below |
| Ship a change to production | Merge to `main` and push. [Production → Ship a change](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#ship-a-change) says what happens and how to check it |
| Change a setting or secret | [Production → Change a setting or secret](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#change-a-setting-or-secret) |
| Put the servers up from scratch | [Set up the servers](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/server-setup.md) |
| Fix something that is broken | [Production → When something is wrong](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md#when-something-is-wrong) |
| Look up any application's port, addresses, tunnels, files or branch | [Applications](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/applications.md) |
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

## Run it

On your PC, with Docker Desktop running:

1. Write the settings. In IntelliJ's terminal, in The-Hub-Database's folder
   ([Which terminal](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md#which-terminal)):

   ```powershell
   .\env\make-env.cmd
   ```

   It writes `.env.development` and `.env.production` into this checkout, and every other one beside it.
   Never edit or commit them: change `~/cranklyradix-env/secrets.env` and run it again.
   **Check:** `.env.development` is in the root of this checkout.
2. Open `compose.development.yaml` in IntelliJ and press the ▶ beside `services:`, or pick
   **Weather (development)** in the run configurations, or in a terminal here:

   ```bash
   docker compose -f compose.development.yaml up -d
   ```

   It starts four containers: `weather-db` (its database, PostGIS, on `localhost:5434`), `weather-app` (Weather built from this checkout), `weather-backup` (a nightly dump into `backups/`) and `weather-tunnel` (weather.surefirehudson.com). Every run rebuilds the app; the database keeps its data.
   **Check:** `docker compose -f compose.development.yaml ps` lists the four, `weather-db` healthy.
3. Open http://localhost:8082/login and sign in as `operator`, code `12345678` (`DEV_ADMIN_CODE` in the secrets file). The console opens at http://localhost:8082/console/map.
   **Check:** http://localhost:8082/actuator/health/readiness says `UP`.
4. After a code change, run step 2 again: only `weather-app` is rebuilt and replaced.
   **Check:** step 3 again.

### Debug it from IntelliJ

1. Start `compose.development.yaml` once (step 2) for its database on `localhost:5434`.
2. Stop the `weather-app` container (Services → Docker → the container → Stop).
3. Run `au.gully.Application` from IntelliJ (Debug for breakpoints), or `./mvnw spring-boot:run`.
   A run from here does not read `.env.development`: on a database that has no console user yet, set `WEATHER_CONSOLE_CODE=12345678` in the run configuration, or the start stops with `WEATHER_CONSOLE_CODE must be exactly 8 digits`. Without `GOOGLE_WEATHER_KEY` the Google upstream is off.
4. When done, stop it and press ▶ on the compose file to put the container back.

### Build and test

With JDK 25:

```bash
./mvnw -B -ntp verify
```

The unit tests need nothing; the one end-to-end test starts a throwaway Postgres with Testcontainers and is skipped where Docker is not available.
**Check:** `BUILD SUCCESS`. This is what CI runs on `main`.

## Deploy it

1. Verify locally first (**Build and test** above): CI runs only on `main`.
2. Merge to `main` and push. CI (`.github/workflows/ci.yml`) builds and tests, then pushes
   `ghcr.io/jlhudson/weather-app`, tagged `main` and the commit.
3. Nothing more to do: the `weather` Portainer stack on the Database & Core (`vps-a`) server runs `compose.production.yaml`
   from `main` with `.env.production`, and Watchtower replaces the container at the next :00 or :30 past the hour. A
   change to `compose.production.yaml` itself is redeployed by Portainer when `main` changes.

**Check:** the CI run on `main` is green, and after the next :00 or :30 past the hour Portainer shows `weather-app` recreated on
the new image. CI keeps the last ten images, each also tagged by its commit, to roll back to.

| | Development | Production |
|---|---|---|
| Address | http://localhost:8082 · https://weather.surefirehudson.com | https://weather.cranklyradix.com.au |
| Tunnel | **Development - Weather** → `http://app:8082` | **Production - Weather** → `http://weather-app:8082` |
| Compose file | `compose.development.yaml`: builds this checkout | `compose.production.yaml`: pulls `ghcr.io/jlhudson/weather-app:main` |
| Settings | `.env.development` | `.env.production`, loaded into the Portainer stack |
| Spring profile | `development` | `production` |
| Database | `weather-db`, on `localhost:5434` | `weather-db`, not published |
| Backups | `weather-backup`, nightly into `backups/` | `weather-backup`, nightly into the `weather-backups` volume |
| The Hub | http://host.docker.internal:8080 (only the Feedback page calls it) | http://hub-app:8080 (same server, `apps` network; only the Feedback page calls it) |

The Hub reaches Weather with the pre-shared key `WEATHER_KEY_HUB` here and `HUB_WEATHER_API_KEY` there; Flyway creates the schema on the first boot.

Every application's README has these three sections in the same words, with its own names and values
([Applications](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/applications.md)).

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
