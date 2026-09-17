# 03 · Configuration

[← Docs index](README.md)

**The shipped `application.yml` is almost empty, and that is deliberate** (D-147). Every tuning value
carries its default in the record that reads it, as a `@DefaultValue` on a `@ConfigurationProperties`
record. This file is the readable answer to "what does it do by default, and what may I change".
Naming a key in the yml overrides one line of it; none of them needs to be named.

Secrets never appear in the yml. They arrive from `.env` (D-101).

**Four prefixes, four records.** `weather.app` is a separate prefix from `weather` on purpose:
`WeatherProperties` binds `weather`, and two records on one prefix only works in Boot while their key
sets do not overlap — a property nobody can see from either file, and one the next key added to either
would quietly break.

| Prefix | Record |
|---|---|
| `weather` | `au.weather.service.WeatherProperties` |
| `weather.app` | `au.weather.config.WeatherAppProperties` |
| `weather.terrain` | `au.weather.terrain.TerrainProperties` |
| `weather.diagnostics` | `au.weather.diagnostics.DiagnosticsProperties` |

---

## 3.1 `weather` — the service

| Key | Default | What it is |
|---|---|---|
| `weather.enabled` | `true` | Off, nothing is fetched and no sweep is scheduled. |
| `weather.timeout` | `15s` | Per-upstream request timeout. |
| `weather.contact` | `https://github.com/jlhudson/Weather-Modelling` | Identifies this deployment in the User-Agent. **MET Norway blocks callers who do not identify themselves**; everywhere else it is politeness. Reads `WEATHER_CONTACT`. |
| `weather.zone` | `Australia/Adelaide` | The zone daily aggregates are cut on, for providers reporting no zone of their own. Open-Meteo resolves the real zone at the point, and that one wins. |
| `weather.forecast-days` | `3` | How far the daily outlook runs. Short on purpose: Open-Meteo weights a call by variables × span, so a fortnight is several calls. |
| `weather.forecast-hours` | `72` | How far the hourly series runs. 72 covers every forecast day, so each gets its own driest hour. |
| `weather.order` | `open-meteo, google` | Provider ids in preference order; the first configured, healthy and in-allowance one answers. Google sits last: it is the one that bills. `open-meteo-bom` is absent because the Bureau has open-data delivery suspended. |

### `weather.cache` — the two-axis cache

| Key | Default | What it is |
|---|---|---|
| `ttl` | `30m` | How long a reading is reusable outright. |
| `max-stale` | `3h` | The oldest reading that may still be served when every provider is refusing. A degraded answer that says how old it is beats no answer. |
| `reach-km` | `20` | Nominal reach. The governor moves it between its floor and ceiling; this is only where it starts. |
| `vertical-weight` | `0` | Metres of horizontal cost per metre of height difference. At 67, 300 m of climb costs as much as 20 km of travel. **Ships at zero**, which reproduces the old horizontal-only rule exactly. |
| `max-anchors` | `500` | Ceiling on live anchors, least-reused evicted first. |
| `sweep-interval` | `15m` | (Declared; the sweep runs on `weather.refresh.tick-interval`.) |
| `retain-for` | `48h` | How long an expired anchor row is kept, so a restart sees recent history. |

### `weather.refresh`

| Key | Default | What it is |
|---|---|---|
| `tick-interval` | `5m` | How often the sweeper governs, sweeps the cache and backfills terrain. |

The Hub's `Refresh` also held `on-raise`, `on-upgrade`, `open-incident-interval`,
`move-sigma-multiple`, `max-per-tick` and `max-incident-age`. Those were read only by
`WeatherManager`'s incident half, which stayed behind (D-249). The record survives for the one key
that is still ours, so `weather.refresh.tick-interval` means here what
`hub.weather.refresh.tick-interval` meant there.

### `weather.governor`

| Key | Default | What it is |
|---|---|---|
| `enabled` | `true` | Off, everything rests at the configured nominals and the reason says so. |
| `reach-floor-km` / `reach-ceiling-km` | `15` / `50` | The band the anchor reach moves in. **The floor is the operating value**; the ceiling is for the day Open-Meteo is down. |
| `drought-floor-km` / `drought-ceiling-km` | `25` / `100` | The same for the drought cell radius. |
| `river-floor-km` / `river-ceiling-km` | `5` / `15` | The same for the river cell radius. Tight, because discharge belongs to a particular watercourse. |
| `ttl-ceiling` | `90m` | The widest the time-to-live may go. The *floor* is a hard 30 minutes that configuration cannot lower, because a configurable floor is not a floor. |
| `forecast-days-ceiling` | `7` | The widest daily span the governor may ask for. |
| `dead-band` | `0.15` | How far the signal must move before anything changes, so the reach does not twitch on noise. |
| `steps` | `8` | Notches from floor to ceiling. One per recompute, so a full traverse takes eight hours. |
| `interval` | `1h` | How often the governor may step. It runs off the sweep tick, so the real cadence is the coarser of the two. |
| `headroom-holds` | `2` | Consecutive intervals of headroom required before stepping *toward* the floor. Asymmetric on purpose: relief should be immediate, spending more should be earned. |

### `weather.fire`

| Key | Default | What it is |
|---|---|---|
| `enabled` | `true` | |
| `fallback-drought-factor` | `8` | 0–10, used **only** when drought is disabled or its spin-up fails. |
| `basis` | `drought factor assumed from configuration: the spin-up did not run` | The sentence that travels with an index built on that fallback, so a real number and an assumed one are never confused. |

There is deliberately no grassland index. GFDI needs curing and fuel load, which are not weather.

### `weather.drought`

| Key | Default | What it is |
|---|---|---|
| `enabled` | `true` | |
| `spin-up-days` | `365` | How far back the integration runs. A year is enough for the assumed starting deficit to have washed out. |
| `cell-radius-km` | `50` | How far one spun-up cell may be reused. Far larger than the weather reach, because soil moisture is far smoother. |
| `archive-lag-days` | `5` | How far behind real time the reanalysis archive is assumed to run; the forecast endpoint's `past_days` closes the gap. |
| `call-weight` | `6` | A year of daily data is several allowance units, not one. |
| `default-annual-rainfall-mm` | `550` | Used only if the window is too short to derive it. |

### `weather.flood`

| Key | Default | What it is |
|---|---|---|
| `enabled` | `true` | |
| `discharge-enabled` | `true` | Off, rainfall and saturation still answer; only the river goes null. |
| `cell-radius-km` | `5` | Tight on purpose: GloFAS answers for the largest river within about 5 km, so a wide reuse radius would confidently report the wrong watercourse. |
| `call-weight` | `1` | |
| `forecast-days` | `7` | How far the discharge forecast runs. |

---

## 3.2 `weather.app` — the application

| Key | Default | Env |
|---|---|---|
| `weather.app.console.code` | `12345678` | `WEATHER_CONSOLE_CODE` |
| `weather.app.console.lockout-after` | `5` | — |
| `weather.app.console.lockout-for` | `15m` | — |
| `weather.app.api.cors-origins` | *(empty)* | `WEATHER_CORS_ORIGINS` |
| `weather.app.api.requests-per-minute-per-key` | `600` | — |

One console user, `operator`, an 8-digit code, locked for fifteen minutes after five consecutive
failures (D-123). The code **must** be exactly eight digits or the service refuses to create the user.
`cors-origins` is empty by default — same-origin only until a consumer is actually named — and `*` is
filtered out if it is ever set.

## 3.3 `weather.terrain`

| Key | Default | What it is |
|---|---|---|
| `weather.terrain.enabled` | `true` | Off, every anchor comparison is horizontal and the cache's note says so. A supported state, not a degraded one — the vertical weight ships at zero anyway. |

## 3.4 `weather.diagnostics`

| Key | Default | What it is |
|---|---|---|
| `keep-errors` | `7d` | How long an error signature is kept after it was last seen. |
| `keep-warnings` | `2d` | Shorter: a warning that has stopped is not worth a morning's attention. |
| `queue-capacity` | `2000` | Captured lines that may wait for the drain. Past it, lines are dropped and counted — never blocked on, because the logger must never wait for the database. |
| `drain-every` | `5s` | How often the queue is written to `log_event`. |
| `sweep-every` | `1h` | How often retention is applied. |
| `window` | `24h` | The default look-back of every diagnostics read. |

---

## 3.5 Environment variables

Everything in `.env.example`, and nothing else is read from the environment.

| Variable | Used by | Required |
|---|---|---|
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `compose.yaml` and the datasource fallback | yes |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | the datasource, overriding the above | no |
| `WEATHER_CONSOLE_CODE` | the console login | yes in production |
| `WEATHER_CORS_ORIGINS` | the API CORS allowlist. Comma-separated. **Never `*`.** | no |
| `WEATHER_CONTACT` | the User-Agent MET Norway checks | yes if MET Norway is in `weather.order` |
| `GOOGLE_WEATHER_KEY` | `GoogleWeatherProvider`. Empty is a supported state: the provider reports itself unconfigured and is skipped. | no |
| `CARTO_API_KEY` | the console basemaps. Empty leaves CARTO off the basemap list. | no |
| `CLOUDFLARE_TUNNEL_TOKEN` | the `cloudflared` service in `compose.yaml` | no |
| `TZ` | `UTC`, explicitly, everywhere | yes |

## 3.6 Spring's own

`server.port` is `8082`. `spring.jpa.hibernate.ddl-auto` is `update`: Hibernate owns table creation
while the schema is still growing (D-091, D-115) and Flyway owns the alterations it cannot perform
(D-206). **Never set `validate` until every table has a migration behind it** — see
`src/main/resources/db/migration/README.md`. `spring.flyway.baseline-version` is `0` so a migration
runs on an empty database and a populated one alike.
