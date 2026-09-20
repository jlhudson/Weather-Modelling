# 03 · Configuration

[← Docs index](README.md)

Fewer settings, none dead (docs/06 item 10). One group per thing a deployment genuinely varies;
everything else is a constant beside the code that knows why. Every value carries its default in the
record that reads it — `GullyProperties`, `TerrainProperties`, `DiagnosticsProperties` — and
`Settings` prints every effective value once at startup, so what a deployment is actually running with
is in the log.

Secrets never appear in the yml. They arrive from `.env`.

## 3.1 `gully` — the service

| Key | Default | What it is |
|---|---|---|
| `gully.enabled` | `true` | Off, nothing is fetched and nothing is polled; the API answers from what it holds. |
| `gully.contact` | the repository URL | Who is running this deployment, sent to every upstream in the User-Agent. The Bureau asks for it. `WEATHER_CONTACT`. |
| `gully.zone` | `Australia/Adelaide` | The zone a daily aggregate is cut on where a hexagon has no zone of its own yet. |
| `gully.refresh-ahead` | `3m` | How early before a reading's expiry a served hexagon is refreshed in the background. |
| `gully.cold-after` | `24h` | How long a hexagon nobody asks about keeps its forecast in memory. The hexagon itself stays. |
| `gully.upstreams.order` | `open-meteo, google` | Which upstreams are on, in the order tried. Each after the first is the overflow for the one before. |
| `gully.sources.bureau` | `true` | The Bureau's station files (every ten minutes) and warnings (every five). |
| `gully.sources.cfs` | `true` | The CFS district ratings (hourly) and fire ban district shapes (daily). |
| `gully.sources.rivers` | `true` | GloFAS river discharge, once a day per river cell. |
| `gully.history.backups` | *(empty)* | The directory the nightly export of the ground's record goes to; empty for none. `WEATHER_BACKUPS_DIR`; `/backups` in the container. The record itself is kept five years by the service (`History.KEEP`). |
| `gully.console.code` | `12345678` | The one console user's 8-digit code. `WEATHER_CONSOLE_CODE`. |
| `gully.console.lockout-after` / `lockout-for` | `5` / `15m` | Consecutive failures before the lockout, and how long. |
| `gully.api.cors-origins` | *(empty)* | Browser origins allowed to call the API. Never `*`. `WEATHER_CORS_ORIGINS`. |
| `gully.api.requests-per-minute-per-key` | `600` | The per-minute ceiling on one key, answered in the `RateLimit-*` headers. |
| `gully.api.requests-per-day-per-key` | `100000` | The daily cap on one key. |

## 3.2 `gully.terrain` — the mounted rasters

| Key | Default | What it is |
|---|---|---|
| `gully.terrain.elevation-file` | *(empty)* | A GeoTIFF of ground height, such as Geoscience Australia's 9-second DEM. `WEATHER_ELEVATION_FILE`. |
| `gully.terrain.land-cover-file` | *(empty)* | A GeoTIFF of land-cover or land-use classes, such as ABARES' catchment-scale land use. `WEATHER_LAND_COVER_FILE`. The class mapping is `<file>.classes.properties` beside it, lines like `330-339=cropland`; without one, ABARES' secondary classes are assumed. |

Both optional; without a file the hexagon carries the upstream model's own elevation, no slope and no
land use. In the container the files live on the `weather-data` volume at `/data`.

## 3.3 `gully.diagnostics`

| Key | Default | What it is |
|---|---|---|
| `keep-errors` / `keep-warnings` | `7d` / `2d` | How long a signature is kept after it was last seen. |
| `queue-capacity` | `2000` | Captured lines that may wait for the drain; past it, lines are dropped and counted. |
| `drain-every` / `sweep-every` | `5s` / `1h` | How often the queue is written, and retention applied. |
| `window` | `24h` | The default look-back of every diagnostics read. |

## 3.4 The constants

What is not configuration, and where it lives: the hexagon's width and the grid's anchor
(`Grid.CELL_KM`, 17 km; `Grid.ANCHOR_LAT` and `ANCHOR_LON`, the Murray Bridge Golf Course — change
either and the hexagon-keyed tables are reset at the next start, the service says so in the log);
a station's reach — how far outside a hexagon a station still counts as its own — is neither a
constant nor a property but a value turned on the console map and kept in the `setting` table
(`Reach`, W-18): `Grid.DEFAULT_STATION_REACH_KM`, a quarter of the width, until one is set, and the
start log says which is in force;
every upstream's host, model, licence, allowance, cost per fetch, per-minute limit and pause
(`OpenMeteo.SPEC`, `GoogleWeather.SPEC`); the budget guard (90%) and the breaker's trip count (3);
the Bureau's product identifiers and the age at which an ask reads a file again (`StationFile`,
`WarningFiles`, `StationReader.EVERY` 15 min, `WarningsReader.EVERY` 5 min); the station ledger's
cadence (six hours) and the rain day (9 am); the CFS URLs and the same ages (`Ratings.EVERY` an hour,
`Districts.EVERY` a day); the state boxes an ask reads files for (`States`); the interpolation's
rings (2), share (0.30 — 2 of 6, 6 of 18), power (2) and lapse rates (−6.5 and −2.0 °C/km,
`Interpolation`); the elevation lattice (7 across, `HexagonStore`); the land-cover service, coverage,
pixels across a hexagon (96), counting lattice (48) and how many years back the latest map is looked
for (3) (`DeaLandCover`); the drought window (365 days), the archive lag (5 days) and the
station reach (75 km) (`Drought`); a forecast's life (3 h, 5 h once the day's allowance is 70% spent,
`Life`), the drift tolerances (3 °C, 20 points, 15 km/h, 5 mm, `Drift`) and the hour before a forecast
thrown out for drift is fetched again (`HexagonStore.REFETCH_AFTER_DRIFT`); the river cells (5 km) (`Rivers`); how long a
row of the ground's record stands for (three hours either side, `History.STANDS_FOR`) and how long the record is kept (five years, `History.KEEP`); the grassland fuel load (4.5 t/ha,
`FirePictures`); the wind-change window (48 hours); the refresh executor and the tile cache.

## 3.5 Environment variables

| Variable | Used by | Required |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `compose.yaml` and the datasource fallback | yes |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | the datasource, overriding the above | no |
| `WEATHER_CONSOLE_CODE` | the console login | yes in production |
| `WEATHER_CORS_ORIGINS` | the API CORS allowlist. Comma-separated. **Never `*`.** | no |
| `WEATHER_CONTACT` | the User-Agent on every upstream request | recommended |
| `GOOGLE_WEATHER_KEY` | `GoogleWeather`. Empty is a supported state: the upstream reports itself unconfigured and is skipped. | no |
| `CARTO_API_KEY` | the console basemaps | no |
| `WEATHER_ELEVATION_FILE`, `WEATHER_LAND_COVER_FILE` | the terrain rasters | no |
| `WEATHER_BACKUPS_DIR` | the nightly history export; `/backups` in the container | no |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` for structured JSON logs (the container default), empty for the plain pattern | no |
| `CLOUDFLARE_TUNNEL_TOKEN` | the `cloudflared` service, under the `edge` profile | only with `edge` |
| `TZ` | `UTC`, explicitly, everywhere | yes |
| `WEATHER_PORT`, `WEATHER_DB_PORT`, `WEATHER_MEM_LIMIT`, `COMPOSE_PROFILES` | `compose.yaml` only | no |

## 3.6 Spring's own

`server.port` is `8082` inside the container. Flyway owns the schema outright (`db/migration`); there
is no entity manager and no `ddl-auto`. `spring.mvc.problemdetails.enabled` is on, so framework errors
are problem details like ours. Compression is on for JSON, GeoJSON and the console. Readiness includes
the database.
