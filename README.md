# Weather

One point in, the weather out.

A standalone Spring Boot service holding what used to be the weather half of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database): an anchor cache that answers most
readings without a call, a chain of forecast providers with a ledger and a governor over their free
allowances, a 365-day drought spin-up behind the fire indices, and GloFAS river discharge behind the
flood block. It was split out under D-242 — three applications, three databases, HTTP between them —
and D-249: this service holds the cache, the Hub holds the question of when to ask. Since D-252 the
Hub keeps no fallback: an incident this service does not answer for is *owed* a reading and is asked
about again on every sweep until it gets one.

Java 25, Spring Boot 4.1.1, PostgreSQL 18 + PostGIS 3.6, one Maven module. Port **8082** inside the
container; `WEATHER_PORT` is the host side of the compose mapping.

**An overhaul is being chosen from.** [docs/06-overhaul.md](docs/06-overhaul.md) is the catalogue of
what to rebuild — a pre-warmed grid in place of the anchor cache, time-series storage, a real v1 API,
AFDRS, observations and warnings, and the language question — with what each one deletes and what it
changes on the Hub. Until a proposal is ticked, this README describes what runs today.

## The API

Every `/api/**` route needs an API key in `X-Api-Key` or `Authorization: Bearer`, issued on this
service's own `/console/api-keys`. Answers are JSON, times are ISO-8601 UTC, errors are
`{"error": "..."}`. Every GET carries a strong ETag hashed from its body and would answer `304` to a
matching `If-None-Match` — but every body carries `generatedAt`, so the hash changes on every request
and the `304` never fires in practice (docs/02 §2.0).

| Route | What it answers |
|---|---|
| `GET /api/weather?lat=&lon=&forecast=false&force=false` | The reading at a point: provenance, current conditions, fire, flood, drought, and the days and hours when `forecast=true`. `force=true` skips the cache and spends allowance. |
| `GET /api/weather/status` | Every provider with its limits and what it has spent, the cache counters, the governor's current tuning, and the drought and river cell counts. |
| `GET /api/weather/spend?provider=&since=` | Allowance units the ledger recorded for one provider since an instant. |
| `GET /api/weather/spend/daily?provider=&from=&to=` | The same, cut into UTC days. At most 62. |
| `GET /api/weather/coverage.geojson?hourly=false&hours=24` | The cache as a FeatureCollection: four concentric outlines over the ground it covers, plus every anchor and cell. |
| `GET /api/diagnostics?window=` | The docs/26 shape, with a `weather` block. Plus `/logs`, `/logs/{id}` and the two `DELETE`s. |
| `GET /actuator/health`, `/health/liveness`, `/health/readiness` | Public. |

Full shapes and curl examples: [docs/02-api.md](docs/02-api.md).

## Running it

**With compose.** Copy `.env.example` to `.env`, set `WEATHER_CONSOLE_CODE` and
`POSTGRES_PASSWORD`, then:

```
docker compose up -d        # builds the image from this checkout on every up
```

The console is at <http://localhost:8082/console/weather>, username `operator`, the 8-digit code from
`.env`. That is the whole stack — Postgres with PostGIS, this service, and with `COMPOSE_PROFILES=edge`
and a `CLOUDFLARE_TUNNEL_TOKEN` in `.env` the Cloudflare connector that publishes it at
`weather.surefirehudson.com` — and it is the same file Portainer deploys onto a VPS from this
repository's `main`, with the machine's values as the stack's environment variables (The Hub's
[28 · Deployment](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/28-deployment.md) is
the runbook for all four). Beside the Hub on one development machine it keeps its own Postgres, on
host port 5435 (`WEATHER_DB_PORT`; the Hub's is 5432, IncidentWatch's 5433, Operations' 5434), and the
Hub reaches it at `http://host.docker.internal:8082` (`HUB_WEATHER_URL` in the Hub's `.env`) with a key
issued on `/console/api-keys` here. Ports are on loopback only; IncidentWatch, 8082 inside its own
container, publishes on 8083.

**From the IDE.** `au.weather.WeatherApplication`, with a PostGIS database reachable at
`localhost:5435` through `SPRING_DATASOURCE_URL` — this repository's compose Postgres, started alone
with `docker compose up -d db`. Hibernate creates the eight tables on the first boot: the four that
came from the Hub (`weather_anchor`, `weather_call`, `drought_cell`, `river_cell`) and this
service's own `api_key`, `api_access_log`, `console_user` and `log_event`. No migration is needed for
an empty database. Set `GOOGLE_WEATHER_KEY` only if you want the billed fallback — the defaults use
Open-Meteo alone.

**The build.** `./mvnw -B -ntp verify`. There is no `@SpringBootTest` and nothing in the test suite
needs a database or the network.

## Configuring it

The shipped `application.yml` is almost empty by design (D-147). Every tuning value carries its
default in the record that reads it — `WeatherProperties`, `WeatherAppProperties`, `TerrainProperties`,
`DiagnosticsProperties` — and naming a key in the yml overrides one line of it. The environment
variables are the handful that differ between one machine and the next, and they are all in
`.env.example`. Every key with its default: [docs/03-configuration.md](docs/03-configuration.md).

## The docs

- [docs/01-what-it-does.md](docs/01-what-it-does.md) — the cache, the provider chain, the governor, drought and flood, and what is deliberately not here
- [docs/02-api.md](docs/02-api.md) — the contract, with curl
- [docs/03-configuration.md](docs/03-configuration.md) — every `weather.*` key and every environment variable
- [docs/04-migration-from-the-hub.md](docs/04-migration-from-the-hub.md) — the four tables to dump and restore, and the Hub's side of the wiring
- [docs/05-decisions.md](docs/05-decisions.md) — D-242, D-249 and D-252 as agreed, plus this service's own three
- [docs/06-overhaul.md](docs/06-overhaul.md) — the overhaul catalogue: where we are, fifteen proposals with effort, deletions and Hub impact, and the sequence to do them in
