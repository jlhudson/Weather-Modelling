# 04 · Migration, from the Hub and from the old service

[← Docs index](README.md)

Two migrations have happened here. Phase 1 of `The-Hub-Database/docs/27-the-split.md` moved the
weather code and four tables out of the Hub on 17–18 September 2026; the overhaul of 19 September
2026 replaced that code and its tables with Gully's. This file is what is left to know about both.

## 4.1 The database

Gully's schema is Flyway's (`db/migration/V1__gully.sql`), and it runs over the old service's database
without help: the four tables Hibernate built for the platform — `api_key`, `console_user`,
`log_event`, `api_access_log` — are declared `IF NOT EXISTS` with the columns Hibernate gave them, so
the rows survive, the key issued to The Hub among them. The end-to-end test boots against exactly that
database, planted from the live schema of 18 September 2026.

The four weather tables of the old service — `weather_anchor`, `weather_call`, `drought_cell`,
`river_cell` — are not read any more and are not touched: retiring data is an operator's act. Once you
have looked at them:

```sql
drop table if exists weather_anchor, weather_call, drought_cell, river_cell;
```

The drought cells were the ones the old docs said never to lose. They are not worth keeping now: the
new drought state is per hexagon area and is rebuilt from the Bureau's station ledger and the archive
on first ask, at about six allowance units per area, once.

Gully's own tables: `hexagon`, `reading_snapshot`, `station`, `station_sample`, `upstream_call`,
`grass_curing`, `river_discharge`. The one to be careful with is `reading_snapshot` — the history
nothing else holds — which is why it gets a nightly export to the backups volume.

## 4.2 The Hub's side

Three things have to be true.

**1. A key, issued here.** On `/console/api-keys`, issue one to the consumer `hub` with the `ALL` scope
(or `READINGS`; the Hub's map layer needs `LAYER` too). The plaintext is shown once and goes into the
Hub's `.env` as `WEATHER_API_KEY`. A key issued by the old service keeps working.

**2. The Hub's property.** Unchanged: `hub.weather.base-url` and `hub.weather.api-key`. The Hub's
`HttpWeatherClient` now calls `/api/v1/readings`, passes `incident=` and `at=` when it asks late, and
reads the v1 shape; its copy of the contract is `hub-services/src/main/resources/contract/reading.schema.json`.

**3. Compose.** This repository's `compose.yaml` is the one deployment. The service, container and
volume names stayed `weather-*` so the deployed stack keeps its database and The Hub's
`HUB_WEATHER_URL` keeps working.

## 4.3 What the Hub changed in the overhaul

- **`WeatherReading`** reads the v1 shape: `available`, `source`, `at`, `current`, `station`, `fire`
  (with `grass`, `official`, `wind`), `warnings`, `forecast`.
- **`MetricsManager`** no longer computes the grassland index: the McArthur grass meter, the curing and
  the fuel load moved here (docs/06 items 4 and 18), and the metrics component carries the reading's
  fire block whole — forest, grass, AFDRS, official — plus the Hub's own spread direction. The Hub's
  `GrassFireDanger`, its curing register and the curing page went; the fuel-type lookup stays for the
  boundaries component.
- **`WeatherManager`** passes the incident's id on every ask and its start time on a late one, so the
  history here is written for incidents and answered for their start.
- **The console map's weather layer** draws the hexagon layer.

## 4.4 What the Hub keeps

The decision about *when* to ask (D-249), `WeatherEvent`, `WeatherPanel`, the published-rating ledger
`fire_danger_day` (its D-225, the record of what the public were told per district-day), the
fuel-type lookup for the boundaries component. And no cache and no fallback (D-252).

## 4.5 What a second copy of this service would need

Two instances behind one hostname would each hold their own in-memory cache and each poll the Bureau
and the CFS. That works, wastefully, except for three things: the drought step must run exactly once
per area per day (a `select ... for update skip locked` over `hexagon.drought_computed_for`, or one
instance nominated to step); the history snapshot's three-hour window is per instance until it reads
`last_snapshot_at` back from the row before writing (it does, so two instances write at most two);
and a hexagon fetched on one instance is not in the other's memory until it reloads its row — a
shared cache rebuild would want a `notify` on the `hexagon` table. None of it is built; one instance
is the deployment.
