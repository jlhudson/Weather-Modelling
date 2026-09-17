# Weather

One point in, the weather out.

A standalone Spring Boot service holding what used to be the weather half of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database): an anchor cache that answers most
readings without a call, a chain of forecast providers with a ledger and a governor over their free
allowances, a 365-day drought spin-up behind the fire indices, and GloFAS river discharge behind the
flood block. It was split out under D-242 — three applications, three databases, HTTP between them —
and D-249: this service holds the cache, the Hub holds the question of when to ask.

Java 25, Spring Boot 4.1.1, PostgreSQL 18 + PostGIS 3.6, one Maven module. Port **8082**.

## The API

Every `/api/**` route needs an API key in `X-Api-Key` or `Authorization: Bearer`, issued on this
service's own `/console/api-keys`. Answers are JSON, times are ISO-8601 UTC, errors are
`{"error": "..."}`, and every GET carries an ETag and answers `304` to a matching `If-None-Match`.

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
docker compose up --build
```

The console is at <http://localhost:8082/console/weather>, username `operator`, the 8-digit code from
`.env`. The `cloudflared` service only starts with a `CLOUDFLARE_TUNNEL_TOKEN`; leave it out of the
`up` if you are not publishing this.

In the deployment docs/27 describes, all three applications share one Postgres container with three
databases and this service is a block in the Hub's `compose.yaml`. The `compose.yaml` here is the
standalone one, and it maps its own database to host port 5433 so the two can run side by side.

**From the IDE.** `au.weather.WeatherApplication`, with a PostGIS database reachable at
`jdbc:postgresql://localhost:5432/weather`. Hibernate creates the four tables on the first boot; no
migration is needed for an empty database. Set `GOOGLE_WEATHER_KEY` only if you want the billed
fallback — the defaults use Open-Meteo alone.

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
- [docs/05-decisions.md](docs/05-decisions.md) — D-242 and D-249 as agreed, plus this service's own three
