# 04 · Migration from the Hub

[← Docs index](README.md)

Phase 1 of `The-Hub-Database/docs/27-the-split.md`, steps 5 to 10. The code is here; this is the data
and the wiring.

## 4.1 The four tables

Four tables leave the Hub, and they keep their names exactly so a `pg_dump` restores into this
database without a rename:

| Table | What it holds | If it is lost |
|---|---|---|
| `weather_anchor` | The cached readings, with their payload, point, terrain height and hit count | Rebuilt by the next few hours of traffic, at the cost of allowance |
| `weather_call` | The upstream call ledger the budget counts from | The day's spend reads as zero and the guard is briefly blind |
| `drought_cell` | **A year of integrated rainfall per cell**, as a KBDI and a Griffiths drought factor | **Rebuilt from nothing.** Do not lose this |
| `river_cell` | GloFAS discharge per cell, with its recent series | Re-fetched a cell at a time, one call each |

> **The drought cells are the ones to be careful with** — a year of integrated rainfall per cell,
> rebuilt from nothing if lost. **Dump and restore them; do not re-derive.**
> — docs/27 §27.4

Re-deriving them is not merely slow, it is a different answer: the spin-up would restart from the
assumed starting deficit, and the index would lean on that assumption for months while it washed out.
The rows already hold the washed-out result.

## 4.2 Dump and restore

Boot this service once against an empty `weather` database first, so Hibernate creates the four tables
and their indexes from the entities. Then move the rows:

```
# One table at a time, data only, into the schema Hibernate just built.
for t in weather_anchor weather_call drought_cell river_cell; do
  pg_dump --data-only --table="$t" hub | psql weather
done
```

If you would rather move schema and data together, drop the tables this service created first and let
the dump bring them:

```
psql weather -c 'drop table if exists weather_anchor, weather_call, drought_cell, river_cell'
pg_dump -t weather_anchor -t weather_call -t drought_cell -t river_cell hub | psql weather
```

Either way the `weather` database must have PostGIS: `CREATE EXTENSION IF NOT EXISTS postgis;`. All
four tables carry a geometry column.

Check it landed:

```
psql weather -c 'select count(*) from drought_cell'
psql weather -c 'select count(*) from river_cell'
```

Then **drop the four tables from the Hub**, and only then. They are not read there any more, but a
table that still exists is a table something can still be pointed at by mistake.

## 4.3 The Hub's side

The Hub calls this service through `HttpWeatherClient`, which implements the `WeatherClient` interface
introduced in phase 0. Three things have to be true.

**1. A key, issued here.** On this service's `/console/api-keys`, issue one to the consumer `hub`. The
plaintext is shown once. It goes into the Hub's `.env` as `WEATHER_API_KEY`.

**2. The Hub's property.** One line, and it is the whole of "where is weather" (docs/27 §27.2):

```yaml
hub:
  weather:
    base-url: ${HUB_WEATHER_URL:http://weather:8082}
    api-key: ${WEATHER_API_KEY}
```

Going from one VPS to three is a change to that URL and a Cloudflare hostname. Nothing in the code
moves.

**3. Compose.** The Hub's `compose.yaml` gains a `weather` service built from this checkout, on the
same network and the same Postgres instance with its own database. The `compose.yaml` *here* is the
standalone one — its own Postgres on host port 5433 — for running this service by itself.

## 4.4 What the Hub keeps

Not everything weather-shaped left. These stayed, and a search for them in this repository will
correctly find nothing:

- **`WeatherManager`'s incident half** — when an incident is worth asking about: on raise, on upgrade,
  on a move beyond its own positional uncertainty, otherwise on a hash-staggered interval, capped per
  tick. That is a judgement about incidents (D-249). What came across is the fetching half, as
  `au.weather.startup.WeatherSweeper`.
- **`WeatherEvent`** — an incident event, not a weather value.
- **`core/fuel/GrassFireDanger`, `FuelLoadEntity`, `GrassCuringEntity`, `FireDangerDayEntity`,
  `MetricsManager`** — everything needing fuel load or curing (docs/09).
- **`WeatherPanel`** — the Hub's incident-detail rendering of a weather component.

And the Hub keeps **no cache of its own**. It calls every time; this service's anchor cache does the
work (docs/27 §27.10). When this service is unreachable the Hub logs it once, writes the incident
without a weather block, and re-asks on its next sweep. Fail open, quietly.

## 4.5 What changed in the move

The weather logic itself did not change. What changed around it:

| | In the Hub | Here |
|---|---|---|
| Packages | `au.hub.core.weather`, `au.hub.services.weather`, `au.hub.layers.weather`, … | `au.weather.core`, `au.weather.service`, `au.weather.api`, … |
| Property prefix | `hub.weather.*` | `weather.*` |
| Console/API properties | `hub.console`, `hub.api` on `HubProperties` | `weather.app.console`, `weather.app.api` on `WeatherAppProperties` |
| Terrain | `services/terrain` — a slippy-tile store, ~3,000 lines | `au.weather.terrain.ElevationService` — Open-Meteo's elevation endpoint, memoised (W-1) |
| Host budgets | `HostLimiter` merged the `@Source` annotations with the `HostBudgets` declarations | Declarations only; there is no source register |
| Diagnostics | `sources`, `managers`, `notifications`, `jobs`, `load` blocks | A `weather` block; `log_event.source_id` is kept for shape and is always null |
| Startup | `PhasedStartup` over six phases | Two `ApplicationRunner`s, writing the same `StartupHistory` |
| The map layer | `WeatherLayer implements MapLayer`, in a discovered catalogue | `WeatherLayer` with `at` and `coverage`; `WeatherApiController` routes to them |
| API key prefix | `hub_` | `weather_` — a different issuer, and nothing it issues is valid in the Hub |
| Scheduler bean | `hubTaskScheduler` | `weatherTaskScheduler` |
| `@Table` names | `weather_anchor`, `weather_call`, `drought_cell`, `river_cell` | **unchanged**, which is what makes §4.2 a `pg_dump` |

The console probe moved with its page: what was `POST /console/map/weather/probe` is
`POST /console/weather/probe`, and `static/js/weather-map.js` names the new path.
