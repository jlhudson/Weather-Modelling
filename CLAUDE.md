# CLAUDE.md: building Weather (Gully)

Read this before changing anything here. People start at [README.md](README.md); the shared run and
deploy docs live in [The-Hub-Database/docs](https://github.com/jlhudson/The-Hub-Database/tree/main/docs).
The repository is Weather-Modelling, the app is called Gully in the code (`au.gully`), and Weather in
compose, the Hub and the env files. Port 8082.

## What this is and where it fits

- South Australia's weather from the Bureau's stations, each with a terrain-drawn *reach*; readings,
  drought, fire danger, warnings, forecasts. See `docs/01-what-it-is.md`.
- **Who calls it:** the Hub (8080), from `hub-services/.../weather/WeatherClient.java` in The-Hub-Database:
  `GET /api/v1/reading`, `/api/v1/fire-danger`, `/api/v1/stations.geojson`, `/api/v1/reach.geojson`,
  `/api/v1/warnings`, `/api/v1/warnings.geojson`; and `/api/diagnostics` + `/logs` for the Hub's morning
  agent. The Hub passes readings through untouched, so IncidentWatch's pages read these fields too.
  The Hub's URL and key for this are its `HUB_WEATHER_URL` and `HUB_WEATHER_API_KEY`.
- **Who it calls:** no app of this family. Outside sources only: the Bureau's files, the CFS
  (`cfs/`), Open-Meteo and Google Weather (`upstreams/`), AWS Terrain Tiles (`reach/TerrainTiles`).
  Each is listed with its cost in `docs/04-sources-and-findings.md`.
- **Env vars:** `WEATHER_KEY_HUB` (the pre-shared key the Hub presents, `platform/access/PresharedKeys`),
  `WEATHER_CONSOLE_CODE`, `GOOGLE_WEATHER_KEY` (`upstreams/GoogleWeather`), `CARTO_API_KEY`
  (`console/ConsoleModel`), `POSTGRES_*`, `TUNNEL_TOKEN`.
- These live in `.env.development` / `.env.production`, written by The-Hub-Database's
  `env/make-env.sh` from `~/cranklyradix-env/secrets.env`. **Never hand-edit or commit them**
  (`.gitignore` has `.env` and `.env.*`). A new variable is added to `env.template` in this repository's root, which make-env reads.

## Build, test, run

| Task | Command |
|---|---|
| Build and all tests (what CI runs) | `./mvnw -B -ntp verify` |
| One test class | `./mvnw -B -ntp test -Dtest=KbdiTest` |
| One test method | `./mvnw -B -ntp test -Dtest=KbdiTest#methodName` |
| Run from source | start `compose.development.yaml` once, stop `weather-app`, then `./mvnw spring-boot:run` |
| Run as deployed on a PC | `docker compose -f compose.development.yaml up -d` (or ▶ in IntelliJ) |

- Java 25 (`pom.xml`), Spring Boot 4.1.1, Lombok 1.18.48 as a declared annotation processor,
  Testcontainers 1.21.4. Docker build `maven:3-eclipse-temurin-26`, runtime `eclipse-temurin:25-jre-alpine`.
- The first build downloads Maven dependencies and takes a few minutes; CI allows 25 minutes.
- `EndToEndTest` starts Postgres through Testcontainers and is **skipped without Docker**
  (`@Testcontainers(disabledWithoutDocker = true)`). It holds most consumer-contract checks, so run
  with Docker up.
- A run from source reads no env file: database `localhost:5434/weather`, password `change-me`. On a
  database with no console user it needs `WEATHER_CONSOLE_CODE` (8 digits) or `ConsoleUsers` stops the start.
- `tools/WarningAreasGen.java` is run by hand when the Bureau changes a shapefile; it writes
  `src/main/resources/bureau/warning-areas.geojson`. Its header has the command.

## Layout

One Maven module, `src/main/java/au/gully/`:

| Package | What lives there |
|---|---|
| `api` | `StationsController` (`/api/v1/...`), `UsageController` (`/api/v1/upstreams`) |
| `bureau` | the Bureau's station file, stations, warnings and warning areas |
| `cfs` | CFS fire ratings, bans, districts, curing, grass |
| `console` | Thymeleaf console controllers (`templates/*.html`, `static/css`, `static/js`) |
| `fire` | fire danger maths: `FireDanger`, `CsiroGrassland`, `DryForest`, `Kbdi`, `Outlook`, `WindChange` |
| `fuel` | land cover and point ratings |
| `platform` | `GullyProperties`, `Settings`, `Startup`, `Scheduling`, `Housekeeping`, `HttpFetcher`, `ProductionGuard`, `SecurityConfig`; `platform/access` keys and console users; `platform/diagnostics` |
| `reach` | terrain sampling and the reach polygons |
| `reading` | the answer at a point: `Readings`, `Blend`, `Forecasts`, `Points`, `Rivers`, `Flood` |
| `record` | the six-hourly history, drought, backfill |
| `storage` | `Db` (SQL type conversions), `ConsoleSettings` |
| `upstreams` | Open-Meteo, Google Weather, budget, pacing, breaker, ledger |

Resources: `application.yaml` (+ `-development`, `-production`), `db/migration/V1__gully.sql` to
`V11__fire_danger_day.sql`, `bureau/warning-areas.geojson`, `templates/`, `static/`. Tests mirror the
packages; fixtures in `src/test/resources/fixtures`; contract in `src/test/resources/contract/consumers.json`.

## Conventions used in the code

- **Settings:** `platform/GullyProperties` (and `platform/diagnostics/DiagnosticsProperties`), a record
  with `@DefaultValue` on each field, found by `@ConfigurationPropertiesScan("au.gully")`; `Settings`
  prints every effective value at startup. `application.yaml` holds only Spring wiring and per-machine
  values from env vars. A value that is not per-deployment is a constant beside the code.
- **Secrets in production:** `platform/ProductionGuard` refuses to start under `production` on a blank
  or default console code or database password, or a `dev-key-` key. Add a new secret there and to
  `ProductionGuardTest`.
- **Storage: plain SQL, Flyway.** `JdbcClient` with SQL in the class that uses it; no JPA. Schema
  changes are a new `src/main/resources/db/migration/V12__<what>.sql` (next number); never edit an
  applied migration. Instants go through `storage/Db.ts(...)`.
- **Values:** a missing value is missing (null), never zero; every answer carries its source and time
  (README, W-1). Errors are RFC 9457 problem details.
- **Lombok:** `@Slf4j`, `@RequiredArgsConstructor`, records for value types. `lombok.config` does not
  copy `@Value`, so a bean taking `@Value` writes its own constructor (see `upstreams/GoogleWeather`).
- **Timers:** only two (W-15, `platform/Startup`): the Bureau file every ten minutes and housekeeping
  at 9:30. Everything else is on demand; do not add a clock without a decision.
- **Comments:** a Javadoc per class saying why, citing W-numbers from `docs/02-decisions.md`. Plain,
  terse voice, British spelling.
- **Names:** compose services `db`, `app`, `backup`, `tunnel`; containers `weather-db`, `weather-app`,
  `weather-backup`, `weather-tunnel`; volumes `weather-db`, `weather-backups`; image
  `ghcr.io/jlhudson/weather-app`. The Hub's docs and Portainer rely on these; do not change them.

## Contracts with other applications

- Keys in `X-Api-Key` or `Authorization: Bearer`; scopes `ALL`, `READINGS` (`/api/v1`),
  `DIAGNOSTICS` (`platform/access/ApiKey.Scope`).
- The fields consumers read are listed by answer in `src/test/resources/contract/consumers.json`
  (`reading`, `fire-danger`, `forecast`, `stations.geojson`, `reach.geojson`) and checked with
  `ConsumerContract.missing(...)` in `EndToEndTest` and `ForecastsTest`.
- Its `readMe` names every reader: IncidentWatch's `iw-incidents.js`, `iw-outages.js`, `agency.html`;
  the Hub's `WeatherPanel`, `FireBuddyPackage`, `MetricsManager`, `WeatherManager`, `FireDangerDays`,
  `layer-renderers.js`.
- **A contract change is one round across repos:** change those readers (and the Hub's tests, e.g.
  `WeatherPanelTest`) in the same round as this repo's answer, `consumers.json`, and the README's API table.

## Before you finish a change

1. `./mvnw -B -ntp verify` passes, with Docker running so `EndToEndTest` is not skipped.
2. Docs match the new behaviour:
   - a route or answer shape → the README's API table and `docs/01-what-it-is.md` (section 8, The API);
   - a decision → a new W-number in `docs/02-decisions.md`;
   - a fire danger figure → `docs/03-fire-danger.md`;
   - an outside source, its timing or cost → `docs/04-sources-and-findings.md`;
   - an env var → the README's *Configuring it* and `env.template`;
   - how to run or deploy → the README's *Run it* and, if shared, The-Hub-Database's docs.
3. No secrets, keys, tokens or `.env*` files in the diff.
4. Container, service, volume and image names (`weather-app`, `weather-db`, ...) unchanged.
5. A contract change made in the other repos as well (section 5).
6. CI runs only on `main`, so nothing checks a branch for you: verify locally before merging.

## Deploying

1. Merge to `main` and push. CI (`.github/workflows/ci.yml`) runs `./mvnw -B -ntp verify`, then
   pushes `ghcr.io/jlhudson/weather-app:main` and `:<commit>`, keeping the last ten.
2. Watchtower on the Database & Core (`vps-a`) server replaces `weather-app` within five minutes (label
   `com.centurylinklabs.watchtower.enable` in `compose.production.yaml`). Production is https://weather.cranklyradix.com.au, through the
   tunnel **Production - Weather** → `http://weather-app:8082`.
3. A change to `compose.production.yaml` is picked up by Portainer's GitOps polling of `main`.
4. Settings change in `~/cranklyradix-env/secrets.env` (and `env.template` for a new variable), then
   make-env and the Portainer stack's **Load variables from .env file**; never in this repository.
5. **Check:** the Actions tab is green, then https://weather.cranklyradix.com.au/actuator/health/readiness says `UP`.

Every repository's CLAUDE.md ends with this section in the same words. The fleet's guides, in
The-Hub-Database: [Applications](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/applications.md) (every port, address, tunnel, file and branch),
[Development](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/development.md), [Production](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/production.md),
[Set up the servers](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/server-setup.md).
