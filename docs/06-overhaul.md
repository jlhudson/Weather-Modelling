# 06 · The overhaul catalogue

[← Docs index](README.md)

James, 18 September 2026: *"We want modern, smart, API sensible, and informative! Weather needs to be
significantly more overhauled. We are still very early in development, so I'd be more than happy to
delete chunks of code and restart. We should be ignoring earlier suggestions and decisions if they are
easily overridden and result in significant improvements to our code base. We will choose what to
update and then update The Hub as needed."* And, widening it: *"Seriously, please make a comprehensive
set of suggested updates to Weather that enhance the overall structure, optimisations, cache,
codebase, seriously, all of it. I'm even considering changing Weather to use Python to make use of a
larger set of imports and tools at our disposal. Consider all angles, and throw out previous decisions
that may be hindering the options to overhaul."*

This is the catalogue to choose from. §6.1 says where the service is, honestly. §6.2 is the table to
tick. §6.3 is one section per proposal — the problem as the code has it, the proposal, the shapes it
implies, what the Hub changes, what gets deleted, the risks, and the decisions it overrides. §6.4 is
the order to do them in, in phases that each end with something the Hub can run against.

Effort: **S** a day, **M** a week, **L** two to three weeks, **XL** a month or more, for one person
with Claude. Hub impact: **none** (the Hub runs unchanged), **additive** (the Hub can ignore it until
it wants it), **breaking** (the Hub must change before or with it). Every claim below cites a file;
where a number is an estimate it says so and says how to measure it.

---

## 6.1 Where we are

**Size and shape.** 13,158 lines: 9,973 of Java under `src/main/java/au/weather` (service 3,635,
core 1,910, diagnostics 889, api 758, geojson 518, access 479, console 449, geo 363, http 269,
terrain 200, json 151, startup 159, security 120, config 70, web 30), 1,491 of tests in ten classes,
1,519 of templates and JavaScript. Two commits of substance: the split on 17 September and the
compose fix on the 18th. One consumer, the Hub, through `hub-services/…/weather/HttpWeatherClient`,
and behind it FireBuddy reading `/api/weather` off the Hub's pass-through (`hub-layers/…/WeatherLayer`,
docs/24 of the Hub).

### What is good, and worth keeping whatever is chosen

- **The boundary.** D-242 and D-249 put the right things on the right side. The Hub sees HTTP and
  JSON through one interface (`WeatherClient`), decides *when* to ask, and holds nothing of weather's.
  `hub-managers/…/WeatherManager.java` is 350 lines about incidents and none about providers. That is
  exactly the seam an overhaul needs: everything below it can change without the Hub noticing, as long
  as the JSON holds.
- **The provider abstraction.** `WeatherProvider.Spec` puts an upstream's endpoint, model, licence,
  allowance and cost on the class that calls it, not in configuration. `OpenMeteoProvider.parse`
  refuses a 200 full of nulls rather than caching it. `cooldownFor` reads which window ran out and
  sleeps for that window. These are the right instincts and they survive.
- **The maths.** `core/Kbdi` (Keetch–Byram with per-event interception and the Finkele ceiling),
  `core/FireDanger` (McArthur Mk5 in the Noble, Bary and Gill form, with the deficit carried through
  the forecast), `core/WindChange`, `core/WmoCodes`, `core/Hourly` are pure, documented against their
  sources, and tested by property (`KbdiTest`, `FireDangerTest`, `WindChangeTest`). They port to
  anything, including Python, in an afternoon.
- **The honesty culture.** Nulls are absent and never zero (`Conditions`); every answer carries
  provenance and age; a reading built on an assumed drought factor says `estimated: true` and why; the
  disclaimer is on every body. Keep all of it. The overhaul should make it *more* honest, not less.
- **The tests run without a database or the network** (`README.md`). Ten classes, no Spring context,
  no mocking framework. That is a floor to keep, and §6.3.13 adds the two layers above it.
- **The docs say what the code does**, including where it does nothing (03 now lists the dead keys).

### What is weak, with the file that shows it

1. **The cache answers the wrong question.** `WeatherCache.find` walks every live anchor twice per
   lookup (green tier, then yellow), measuring proximity in three axes to decide whether a reading
   taken *somewhere else, earlier* may stand in. Anchors exist only where something has already asked,
   so the first request anywhere new costs an upstream call at the moment the Hub is waiting, and
   coverage is a by-product of incident history rather than a property of the service. A burst of
   requests for one new point before the first anchor is stored makes one call *each* — there is no
   single-flight in `WeatherService.at` — and creates that many anchors. Every cache hit is a database
   write: `WeatherCache.serveGreen → touch → anchors.save(row)` on every read. The provider's
   `expiresAt` (Open-Meteo: fetched + 15 min) is stored and, as `WeatherLayer.state`'s own comment
   says, "the cache does not currently consult" it; the cache's TTL is 30 min. Two clocks, one used.
2. **The governor governs a knob nobody turns.** `WeatherGovernor` (246 lines) plus `WeatherTuning`
   (81) plus `WeatherGovernorTest` (279) exist so that on the day Open-Meteo is down the anchor reach
   can widen from 15 km to 50 km. Its own Javadoc says pressure "sits near zero and everything rests at
   its floor essentially always". Its position is not persisted, so every restart starts at the 20 km
   nominal and takes about sixteen hours of headroom to reach the 15 km floor (03 §3.1). It moves
   three radii and the forecast span at once, coupling *how much we spend* to *how accurate we are*,
   which are two dials. A fixed refresh schedule against a known quota needs none of it.
3. **The terrain axis ships switched off.** `ElevationService` (184 lines), `TerrainProperties`,
   `Geo.reachMetres`, the two-phase backfill in `WeatherCache` (`freeTerrainBackfill`,
   `backfillTerrain`, the write-through in `touch`) and `ElevationServiceTest` exist for a vertical
   weight that ships at `0`, "which reproduces the old horizontal-only rule exactly" (01 §1.1). About
   480 lines whose default effect is nothing, and whose memo is lost on every restart.
4. **There is no history.** An anchor is a value that expires. Nothing holds what the weather *was*
   at a point at 14:00 yesterday. Under D-252 the Hub's owed incidents are answered hours later with a
   reading taken *then*, attached with `Instant.now()` (`WeatherManager.attach`), which is exactly the
   "stale looks identical to current" failure docs/13 in the Hub is about, one level up. There is no
   climatology for the statistics layer, and no way to ask "what did the forecast say this morning".
5. **The API is maps all the way down.** `WeatherLayer.at`, `WeatherApiController.statusBody`,
   `WeatherJson.*` and `DiagnosticsLayer.summary` all build `Map<String, Object>`; the Hub's
   `WeatherReading` reads them back as maps with string keys. There is no schema, no OpenAPI, no
   `Cache-Control`, no compression; the ETag is strong and hashed over a body that carries
   `generatedAt`, so it never matches (02 §2.0); errors are `{"error": "..."}` in three different
   places (`WeatherApiController`, `ApiKeyAuthenticationFilter`, `SecurityConfig`); there is no batch
   read, so the Hub's sweep of up to twenty incidents is twenty round trips; there is no `at=`.
6. **Configuration and code have drifted.** Read by nothing: `weather.timeout`, `weather.contact`
   (`HttpFetcher.USER_AGENT` is a constant), `weather.cache.sweep-interval`. Unused: `core/Text`,
   `core/WeatherBands` (only its test), `Nodes.parse`, `Numbers.round3`,
   `WeatherCallRepository.findTop50ByOrderByAtDesc`, the conditional-GET half of `HttpFetcher` and
   `Fetched` (every caller passes `null, null`), and most of `geo/Geo` — `hull`, `intermediate`,
   `destination`, `initialBearingDeg`, `normalisedSeparation`, `spatialFeature`, `logit`, `sigmoid`,
   `footprintsOverlap`, `line` are the Hub's incident-matching maths and nothing here calls them.
   Three providers, not four (05 W-2, corrected). The ledger charges Open-Meteo one unit per report
   (`OpenMeteoProvider.GLOBAL`, `callWeight 1.0`) while asking for about thirty hourly variables, and
   Open-Meteo weights calls by variables and span; `cooldownAfter`'s own Javadoc records the day a
   ledger holding a hundred units met a daily-limit refusal. **We do not know what a report costs.**
7. **The console is tables.** `templates/weather.html` is 531 lines of tables; there is no map,
   `static/js/weather-map.js` (326 lines) is loaded by no page and guards on a layer engine that did
   not come across (04 §4.5); there are no charts; the spend numbers are text.
8. **Operations are thin.** Readiness is Spring's `readinessState` alone — the database is not in
   the group, so a service with a dead Postgres reports ready. Provider spend lives only in the ledger,
   not in Micrometer, so `/actuator/prometheus` says nothing about upstreams. Logs are a text pattern.
   `api_access_log` gets a row per request, synchronously, in the filter.
9. **Fire danger is 1980.** `FireDanger.BANDS` are the pre-2022 six (`SEVERE` at 50, `EXTREME` at 75,
   `CATASTROPHIC` at 100), deliberately, because that is what the FFDI was drawn against. Australia's
   operational system since September 2022 is the AFDRS with its Fire Behaviour Index and four public
   ratings; the official rating per district is fetched and kept by the Hub
   (`hub-sources/…/FireDangerRatingsSource`, `FireDangerDayEntity`) and never seen here.
10. **No observations, no warnings.** Everything served is a model. A crew standing in 60 km/h wind
    is told the forecast's 35, and nothing on this API says a Fire Weather Warning is current.

### The decisions to discard, and why now is the cheap time

The service is one day old, has one consumer, and its tables hold nothing worth keeping except
`drought_cell` — which §6.3.7 replaces with a grid spin-up that is *better* than what is there. No
third-party key has been issued. The Hub reads five provenance keys (`provider`, `model`,
`attribution`, `fetchedAt`, `cached` in `WeatherReading`), the `current`, `fire`, `flood`, `drought`
and `forecast` blocks by name, and two of the console's JavaScript popups read `decision`,
`offsetMetres` and `ageMinutes`. That is the whole surface a redesign has to preserve or alias. In six
months there will be history worth migrating, a second consumer, and field names in a mobile app.

| Decision | Where | Recommendation | Why |
|---|---|---|---|
| **The anchor cache design** — proximity key, two time tiers, reach in three axes | Hub docs/09 §9.2, carried by D-249; `WeatherCache` | **Discard.** A pre-warmed grid (§6.3.1) answers every point in South Australia without an upstream call and makes the whole apparatus redundant | It was the right design for a Hub that fetched on demand inside its own process with no schedule. This is a service with a clock. |
| **The governor** | Hub docs/09 §9.2.1; `WeatherGovernor` | **Discard.** Fixed refresh tiers plus per-provider quotas and a circuit breaker (§6.3.6) | It tunes a cache that will not exist, against a spend the ledger cannot see correctly. |
| **W-1 · terrain from the elevation endpoint, memoised** | 05 | **Discard the mechanism, keep the source.** One height per grid cell, fetched once, stored (§6.3.1). The Hub may pass its own point height for a lapse-rate adjustment | The memo existed to compare anchors. There will be no anchors. |
| **W-2 · Open-Meteo, then Google** | 05 | **Amend.** Open-Meteo stays; Google goes, or stays as a point-only fallback behind config (§6.3.6). The stored grid is the fallback | A grid cannot be fed from a provider billed per endpoint per point; the last run, honestly aged, is a better fallback than 300 billed calls. |
| **W-3 · the sweeper governs and sweeps** | 05 | **Replace.** The scheduler *refreshes* on tiers (§6.3.1); there is nothing to govern and only history to sweep | |
| **D-249 · "the anchor cache, the provider chain, the budget governor and the drought accumulators go to Weather"** | Hub docs/16 | **Keep the responsibility, replace every mechanism named.** The *question* still belongs to the Hub; the *answer* is a grid, not a cache | The decision is about which side of the wire; the nouns were the implementation of the day. |
| **D-249 · "the Hub calls every time and does not cache"** | Hub docs/16, docs/27 §27.10 | **Keep, and make it cheap**: a point read from the grid is a memory lookup, and the batch endpoint (§6.3.3) makes a sweep one call | |
| **D-252 · no fallback, incidents owed a reading** | Hub docs/16 | **Keep, and answer it properly**: `?at=` history (§6.3.2) so an owed reading is *as at* the incident, not as at the sweep | |
| **Hub docs/09 · "the grassland index waits for a manager that has fuel load and curing, because they are not weather"** | Hub docs/09 §9.4, D-187 | **Amend.** The *inputs* are not weather; the *calculation* is fire-weather maths and belongs in one place. A stateless fire-behaviour endpoint takes fuel inputs from the caller (§6.3.4) | Two implementations of McArthur in two repositories is one too many; AFDRS makes it three. |
| **Hub D-141 · JSON APIs on demand instead of the Bureau's FTP** | Hub docs/16, FEEDS §9 | **Reopen for observations and warnings only** (§6.3.5). Forecasts stay on Open-Meteo | The FTP was dropped because polling 82 stations for weather nobody had asked about was waste. A service with a grid and a clock is exactly the thing that should poll it. |
| **Hub D-206 / `db/migration/README.md` · Hibernate owns the schema while it grows** | this repo | **Override for the new tables.** Time-partitioned tables need Flyway-owned DDL from the first day (§6.3.2) | `ddl-auto: update` cannot create partitions, and a table that must never be reset is a table that needs a migration history. |
| **D-147 · defaults on the record, an almost-empty yml** | Hub docs/16 | **Keep.** With fewer records (§6.3.10) | |
| **D-242 · three applications, HTTP between them** | Hub docs/16 | **Keep.** Nothing here touches it; the language question (§6.3.12) is invisible to it | |

---

## 6.2 The table to tick

| # | Proposal | Effort | Deletes | Hub impact | Recommendation |
|---|---|---|---|---|---|
| 1 | [A pre-warmed grid over South Australia](#631--a-pre-warmed-grid-instead-of-an-on-demand-anchor-cache) | L | `WeatherCache`, `WeatherAnchorEntity`, the anchor half of `WeatherService`, `Coverage`, the terrain backfill | none while `/api/weather` is aliased | **do first** |
| 2 | [Time-series storage with history, `?at=`](#632--time-series-storage-with-history) | L (with 1) | `drought_cell` and `river_cell` as tables; the JSON `payload` column | additive | **do first** |
| 3 | [API v1 done properly](#633--api-v1-done-properly) | M | `ApiCachingConfig`, the three ad-hoc error shapes, `/api/weather/spend*` after the alias | additive, breaking when the alias goes | **do first** |
| 4 | [AFDRS / Fire Behaviour Index beside FFDI, and the official rating](#634--afdrs--fire-behaviour-index-alongside-mcarthur-ffdi) | M grass and rating; XL forest | nothing; adds | additive | **do** (grass, rating); **later** (forest) |
| 5 | [Observations, warnings, and the sources beyond forecasts](#635--observations-warnings-and-the-sources-beyond-forecasts) | M | nothing; adds | additive | **do** |
| 6 | [Providers and quotas: plain quotas and a circuit breaker](#636--providers-and-quotas) | S | `WeatherGovernor`, `WeatherTuning`, `WeatherHostBudgets`, `HostBudgets`, `HostLimiter`, most of `WeatherBudget`, `GoogleWeatherProvider` (or config-gated) | none | **do first** |
| 7 | [Drought and flood per cell](#637--drought-and-flood-per-cell) | M (with 1) | `DroughtService`, `FloodService`, both cell entities | none | **do** |
| 8 | [Console: a map, charts, a point inspector](#638--console-a-real-map-page-provider-health-and-spend-charts-a-point-inspector) | M | `weather-map.js`, the anchor and cell tables in `weather.html` | additive; the Hub's map switch retargets when `coverage.geojson` retires | **do** |
| 9 | [Contract tests shared with the Hub](#639--contract-tests-shared-with-the-hub) | S | nothing | additive | **do first** |
| 10 | [Configuration: fewer knobs, none dead](#6310--configuration-fewer-knobs-none-dead) | S | `weather.timeout`, `weather.contact` (or wire it), `sweep-interval`, `Cache`, `Governor`, `Refresh`, `TerrainProperties` | none | **do first** |
| 11 | [Security and operations](#6311--security-and-operations) | M | nothing | none | **do** |
| 12 | [Language: Python or Java, on evidence](#6312--language-python-or-java-decided-on-evidence) | S to decide; XL to rewrite | everything, if Python | none (HTTP only) | **decide now: stay on Java; Python as a sidecar when GRIB ingestion is real** |
| 13 | [Structure and codebase hygiene](#6313--structure-and-codebase-hygiene-whichever-language) | M (with 1–3) | the dead code in §6.1, the `Weather` prefix on everything | none | **do** |
| 14 | [Caching and optimisation, layer by layer](#6314--caching-and-optimisation-layer-by-layer) | M | the write-per-read, the linear scans | none; headers are additive | **do** |
| 15 | [The delete list](#6315--the-delete-list) | S | about 4,000 lines | none | **do first** (it is what 1, 6 and 10 leave behind) |

"Do first" is one build: 1, 2, 6, 10, 15 are the same fortnight, with 3 and 9 defining the contract
it must land behind. §6.4 sequences them.

---

## 6.3 The proposals

### 6.3.1 · A pre-warmed grid instead of an on-demand anchor cache

**The problem today.** Coverage is reactive: `WeatherCache` holds readings only where the Hub has
already asked (01 §1.1, "bounded by activity, not by area"). The first incident anywhere new pays an
upstream round trip, inside the Hub's request, at the moment a reading is wanted most. Proximity
matching is a linear scan in three axes (`WeatherCache.best`) over anchors that expire at 30 min and
are last-resort until 3 h, with a governor moving the radius. A cold point, a burst of requests before
the first anchor lands (`WeatherService.at`, no single-flight), a restart past `retain-for`, an
Open-Meteo outage — each is a case the cache handles with a special path, and the console's job is to
watch whether the radius is right (`weather.html`, "A table of coordinates cannot show whether 15 km
is the right reach").

**The proposal.** Replace the cache with a **fixed grid of cells over South Australia, refreshed on a
schedule**, so that a reading for any point in the state is a lookup and never a call. The Hub then
never triggers an upstream request; the budget is spent by the clock, predictably, and the governor's
reason to exist disappears.

*The grid.* Two tiers of latitude/longitude cells, keyed by integer index so a point maps to its cell
by arithmetic (`floor(lat / size)`, `floor(lon / size)`) and PostGIS is not on the read path:

| Tier | Where | Cell | Size at latitude | Cells (estimate) |
|---|---|---|---|---|
| **fine** | the thirteen settled fire ban districts, roughly south of the dog fence plus the Eyre and Yorke peninsulas, Kangaroo Island and the Flinders — about 270,000 km² measured from `CFS_Fire_Ban_Districts`, which the Hub already holds | 0.15° | 16.7 km × 13.8 km at −34° ≈ 230 km² | ≈ 1,200 |
| **coarse** | the two pastoral districts, about 710,000 km² | 0.5° | 55.6 km × 48.7 km at −29° ≈ 2,700 km² | ≈ 260 |
| margin | cells whose centre is over water within 20 km of the coast (the gulfs, the Coorong, KI's straits) | as the tier | | ≈ 5 % more |
| | | | **total** | **≈ 1,500** |

0.15° is chosen because it is the resolution of the finest model Open-Meteo blends over Australia
(BoM ACCESS-G, 0.15°; the ECMWF and GFS members are coarser or comparable). A 0.1° grid (≈ 2,500 fine
cells) would resample the same forecast at twice the cost. H3 hexagons (resolution 5, ≈ 250 km²) give
equal areas and neighbour walks, but need a library on both sides of the wire and align with nothing
upstream; the lat/lon grid needs nothing and matches how Open-Meteo and every NWP file index the
world. The cell definition is a table, not code, so the fine boundary can be moved without a release.

*The refresh.* Open-Meteo counts calls weighted by locations, variables and span, not by HTTP
request. The estimate below assumes **one unit per location for ≤ 10 hourly variables over ≤ 7 days**
and must be measured on the first day against the ledger — §6.1 item 6 says why the ledger's current
weighting is not trusted. Multiple coordinates go in one request (comma-separated lists; the official
clients batch this), so 1,500 cells is fifteen HTTP requests, not fifteen hundred, and the one-second
host politeness in `WeatherHostBudgets` stops mattering.

| Job | Cells | Cadence | Units/cell | Units/day |
|---|---|---|---|---|
| Core forecast: 10 hourly variables (`temperature_2m`, `relative_humidity_2m`, `wind_speed_10m`, `wind_direction_10m`, `wind_gusts_10m`, `precipitation`, `precipitation_probability`, `weather_code`, `vapour_pressure_deficit`, `soil_moisture_0_to_1cm`) for 72 h; 10 daily for 7 days | 1,500 | every 6 h, timed after each ACCESS-G run lands (about 00/06/12/18 UTC + 4 h) | 1 | 6,000 |
| Hot cells: the same request hourly for cells with a live Hub incident, a district rated High or above today, or a point asked about in the last six hours | ≤ 60 | the twenty hours between core refreshes | 1 | 1,200 |
| Fire and flood extras: `dew_point_2m`, `cloud_cover`, `pressure_msl`, `uv_index`, `cape`, `lifted_index`, `boundary_layer_height`, `wind_speed_80m`, `wind_direction_80m`, `soil_moisture_27_to_81cm`, 48 h | 1,200 fine | daily, and with every hot refresh | 1 | 1,200 + |
| Drought step (§6.3.7) | 1,500 | from the grid's own daily rain and maximum temperature | 0 | 0 |
| GloFAS discharge (§6.3.7) | ≤ 200 asked recently | daily | 1 | ≤ 200 |
| Elevation | 1,500 | once; 100 coordinates per request | — | 0 |
| **Total** | | | | **≈ 8,600 of 9,000 guarded (10,000 published); ≈ 258,000 of 300,000 a month** |

That is tight on purpose: it shows the free tier *can* carry a state grid, and it shows where the
first saving comes from (pastoral cells twice a day rather than four times: −600). The release valve
is Open-Meteo's paid API — about €29 a month at the time of writing for a million calls a month and
**commercial use permitted**, which also retires the `commercialSafe: false` the console has to show
today (`OpenMeteoProvider.GLOBAL`, CC BY 4.0 non-commercial). Budget it; it is cheaper than one hour
of tuning a governor.

Apparent temperature and visibility drop out of the request set: the first is a formula (Steadman,
from temperature, humidity and wind — a `science` function), the second is not something a fire
service reads off a model. `Conditions` shrinks from 30 fields to about 20, all of which something
downstream uses.

*Points off the grid.* A request outside every cell (interstate, at sea) falls back to today's
behaviour for that one point: a single-location fetch, stored as an ad-hoc `demand` cell in the same
tables with a one-hour life, refreshed only while it keeps being asked for. The Hub's incidents are
all in South Australia; this path exists so the API never answers "outside coverage" with an error.

*Point to reading.* Nearest cell centre by arithmetic, and the reading carries the cell, its centre,
its elevation and the distance from the point to the centre. Bilinear interpolation across the four
surrounding cells for the continuous fields (temperature, humidity, wind speed, not direction or code)
is a switch, off by default: the model already interpolated once and a second pass is not more truth.
If the caller passes its own `elevationM` — the Hub has a terrain store — the temperature is adjusted
by −6.5 °C/km against the cell's elevation and the adjustment is disclosed in `provenance.adjustments`.
That is the whole of what W-1 was for, done in six lines with no memo.

**Shapes.**

```sql
create table cell (
  cell_id      text primary key,          -- 'f:-234:922' tier:lat_index:lon_index
  tier         text not null,             -- fine | coarse | demand
  size_deg     real not null,
  lat          real not null,             -- centre
  lon          real not null,
  elevation_m  real,                      -- Copernicus GLO-90 via Open-Meteo, once
  district     text,                      -- fire ban district containing the centre, from the Hub's layer
  zone         text not null,             -- 'Australia/Adelaide'
  active       boolean not null default true
);
```

The point read's provenance (full shape in §6.3.3):

```json
"provenance": {
  "source": {"provider": "open-meteo", "model": "best_match", "attribution": "Weather data by Open-Meteo.com, CC BY 4.0",
             "run": {"id": 48213, "fetchedAt": "2026-09-18T04:12:30Z"}},
  "cell":   {"id": "f:-234:922", "tier": "fine", "lat": -35.025, "lon": 138.675, "sizeDeg": 0.15,
             "elevationM": 212.0, "distanceM": 3410},
  "freshness": {"ageMinutes": 47, "state": "fresh", "nextRefreshAt": "2026-09-18T10:00:00Z"},
  "adjustments": [{"kind": "lapse", "field": "temperatureC", "deltaC": -1.3, "basis": "-6.5 C/km against cell elevation 212 m, point 412 m"}]
}
```

**What the Hub changes.** Nothing, in the first release: `/api/weather` keeps its shape with the
five provenance keys the Hub reads unchanged (`provider`, `model`, `attribution`, `fetchedAt`,
`cached` — always `true` now, and documented as meaning "answered from the grid"), and the keys it
stores but does not read (`offsetMetres` → distance to the cell centre, `reachMetres` → the cell's
half-diagonal, `anchor` → the cell id, `anchorLat/Lon` → the centre, `decision` → a sentence naming
the cell and the run). `MetricsManager` reads `fire.ffdi`, `fire.ffdiRating`, `fire.estimated` and the
per-day `fire` blocks — unchanged. Later, under v1, `WeatherManager` gains nothing it needs from this
proposal alone; what it gains is in §6.3.2 and §6.3.3.

**What gets deleted.** `service/WeatherCache.java` (462), `service/WeatherAnchorEntity.java` (89),
`WeatherCacheSelectionTest.java` (162), `geojson/Coverage.java` (134, the union-of-discs outlines),
`Geo.reachMetres` and the vertical-weight plumbing, `terrain/ElevationService.java` as a memo (a
60-line batch client replaces it), `terrain/TerrainProperties.java`, `ElevationServiceTest.java`, the
anchor half of `service/WeatherService.java` (the provider walk, the two tiers, the `describe`
sentence), `WeatherProperties.Cache`, the anchor table and its `coverage.geojson` meaning (the cells
layer in §6.3.3 replaces it), and the `weather.html` anchors table. The `weather_anchor` table is
dropped by hand (D-082's rule that a migration never drops).

**Risks.** The call-weighting assumption: if a lean request costs two units, the schedule halves or
the paid tier is bought — either is a config line, and the ledger says which on day one. Cold start:
a fresh database has no runs; the API answers `503` with a problem body and `Retry-After` until the
fine tier is 90 % covered (about two minutes at fifteen batched requests), or serves on demand for
the asked cell while the grid fills; the Hub's owed backlog absorbs either. Boundary cells over water
answer for the sea; the reading says the cell's centre and the caller can see the distance.

**Overrides.** The anchor cache design (Hub docs/09 §9.2, D-249's nouns), W-1, W-3. Keeps: D-249's
line about where the question lives, D-252.

### 6.3.2 · Time-series storage with history

**The problem today.** Four tables (04 §4.1), each a current-state store: `weather_anchor` holds the
whole `WeatherReport` as a JSON `payload` column and is swept at `retain-for`; `drought_cell` and
`river_cell` hold one row per cell per day and are swept at a week; `weather_call` is the only thing
that accumulates. Nothing answers "what was it at this point at this time", so the Hub's D-252 backlog
gets a *later* reading and `IncidentWatchStatistics.weather` (the Hub's statistics layer) gets today's
weather for a day that may be last month's. There is no climatology, no verification of forecast
against what came, and `docs/27 §27.4` already wrote `?at=` into the API it expected.

**The proposal.** Typed, time-partitioned tables, three time scales, explicit retention, and an
"as-at" read that is honest about how it was answered.

```sql
-- One row per provider refresh. Small.
create table forecast_run (
  run_id       bigint generated always as identity primary key,
  provider     text not null, model text not null,
  tier         text not null,                  -- fine | coarse | hot | demand | extras
  fetched_at   timestamptz not null,
  model_run_at timestamptz,                    -- when the upstream says the model ran, if it says
  cells        int not null, units numeric(8,2) not null, latency_ms int, ok boolean not null, detail text
);

-- The hourly series of one cell from one run, as arrays: one row per cell per run, TOAST-compressed.
-- 1,500 rows per refresh, ~3.5 kB each; ~20 MB a day raw; kept 14 days (~300 MB).
create table cell_forecast (
  run_id     bigint not null references forecast_run,
  cell_id    text   not null references cell,
  from_hour  timestamptz not null,             -- the first timestep
  hours      smallint not null,
  temperature_c real[], humidity_pct smallint[], wind_kmh real[], wind_dir_deg smallint[], gust_kmh real[],
  precip_mm real[], precip_prob_pct smallint[], wmo_code smallint[], vpd_kpa real[], soil_0_1 real[],
  extras     jsonb,                            -- the daily extras set, same array layout, when fetched
  primary key (cell_id, run_id)
) partition by range (from_hour);

-- Best available value per cell per hour: overwritten by each fresher run that covers the hour, so
-- reading it at time T after the fact gives what the freshest run said. 36,000 rows a day (~4 MB);
-- monthly partitions; kept two years hourly (~3 GB uncompressed, ~300 MB with column compression).
create table cell_hour (
  cell_id text not null, valid_at timestamptz not null,
  run_id bigint not null, lead_hours smallint not null,     -- how far ahead this value was a forecast
  temperature_c real, humidity_pct smallint, wind_kmh real, wind_dir_deg smallint, gust_kmh real,
  precip_mm real, precip_prob_pct smallint, wmo_code smallint, vpd_kpa real, soil_0_1 real,
  dew_point_c real, cloud_pct smallint, pressure_hpa real, uv real, cape real, lifted_index real,
  blh_m real, wind_80m_kmh real, wind_80m_dir smallint, soil_27_81 real,
  ffdi real, drought_factor real,                            -- computed at write, so history has the index
  primary key (cell_id, valid_at)
) partition by range (valid_at);

-- One row per cell per local day, kept for ever: the climatology and the drought stepper's input.
create table cell_day (
  cell_id text not null, day date not null,
  tmax_c real, tmin_c real, rh_min_pct smallint, wind_max_kmh real, gust_max_kmh real, rain_mm real,
  kbdi_mm real, drought_factor real, ffdi_max real, fbi_grass real,
  source text not null,                                      -- 'forecast' until the archive confirms, then 'archive'
  primary key (cell_id, day)
) partition by range (day);

create table observation (                                   -- §6.3.5
  station_id text not null, observed_at timestamptz not null,
  temperature_c real, humidity_pct smallint, wind_kmh real, wind_dir_deg smallint, gust_kmh real,
  rain_since_9_mm real, pressure_hpa real, dew_point_c real,
  primary key (station_id, observed_at)
) partition by range (observed_at);

create table discharge_day (                                 -- §6.3.7, keyed on GloFAS's own 0.05° cell
  glofas_cell text not null, day date not null, cumecs real, run_id bigint, primary key (glofas_cell, day)
);
```

Partitions are created ahead by the refresh job (monthly for `cell_hour` and `observation`, yearly for
`cell_day`, weekly for `cell_forecast`) and dropped by retention (`cell_forecast` 14 days, `cell_hour`
two years, `observation` two years, `cell_day` never). Native PostgreSQL 18 partitioning needs no new
image; TimescaleDB would add column compression and `time_bucket`, but the deployment shares one
Postgres with the Hub and Operations (`compose.yaml` there), so changing the image is a three-service
decision — start native, and move to `timescale/timescaledb-ha` (which carries PostGIS) if the
two-year hourly table becomes the thing that costs money. **Flyway owns these tables from the first
migration**: `ddl-auto: update` cannot create partitions and must not be allowed near a table that
holds history, so the migration README's hybrid ends here — `V1__grid.sql` is the schema and
`validate` is the JPA setting for them.

*The `?at=` read.* `GET /api/v1/readings?lat&lon&at=2026-09-17T14:00:00Z` answers from `cell_hour`
at the hour containing `at`, and its provenance says how:

| `freshness.state` | Meaning |
|---|---|
| `fresh` | the latest run, answering for now or the near future |
| `stale` | the latest run is older than the tier's refresh interval plus a grace (an outage is on) |
| `as-at` | a past hour, from the freshest run that had covered it by then (`lead_hours` says how far ahead it was a forecast) |
| `reconstructed` | a past hour that no run covered at the time (this service was down); answered from a later run, `lead_hours` negative |
| `archived` | a past day older than the hourly retention, from `cell_day` |

An owed incident from an outage therefore gets the reading the service *would have given* had it
been up, or says plainly that it is a reconstruction. Both are better than a current reading labelled
with the wrong time.

*What history buys the statistics layer.* `GET /api/v1/cells/{id}/series?from&to&step=day` and a
`climatology` block (percentile of today's FFDI against the cell's own history for the month) once
there is a year of `cell_day`. The Hub's statistics layer stops asking for "the day's weather" at an
agency centroid and asks for the day it means.

**What the Hub changes.** Additive. `WeatherManager.attach` passes `at = view.startedAt()` for
backlog entries (and could for every attachment — the reading "when the incident was raised" is the
record; the refresh on upgrade or movement asks for now). `HttpWeatherClient.at(lat, lon, at)` gains
the parameter. `IncidentWatchStatistics.weather` passes the day. The component's note says "as at".

**What gets deleted.** The `payload` JSON column and `WeatherAnchorEntity`; `DroughtCellEntity` and
`RiverCellEntity` (their content becomes `cell_day.kbdi_mm` and `discharge_day`); the `retain-for`
and `sweep` machinery. The `weather_call` ledger stays as `forecast_run` plus a `provider_call` row
for on-demand and observation fetches.

**Risks.** Disk: at the numbers above it is under 5 GB after two years without compression; alert
on `pg_total_relation_size` (§6.3.11). Partition maintenance is one more scheduled job that must not
fail silently: create three ahead, alert when fewer than two exist. Backfill: history starts on the
day this ships; Open-Meteo's archive (ERA5, 0.25°, and the "historical forecast" endpoint) can fill
`cell_day` back a year for 1,500 cells at about 6 units each — 9,000 units, one night's whole
allowance, so it is a job that runs across three quiet nights or on the paid tier.

**Overrides.** D-206's hybrid (`db/migration/README.md`) for the new tables. The "dump and restore,
do not re-derive" rule for `drought_cell` (04 §4.1) becomes moot: the spin-up runs per grid cell once
(§6.3.7) and the daily step is free thereafter; the old cells are not migrated.

### 6.3.3 · API v1 done properly

**The problem today.** §6.1 item 5. In detail: `WeatherLayer.at` builds a map; `WeatherJson` has
thirty-odd `m.put("…")` calls that are the only definition of the field names; the Hub's
`WeatherReading` reads them back by string; nothing generates a schema; `ShallowEtagHeaderFilter`
produces a strong ETag that never matches; a `400` is `{"error": "lat must be…"}` from the controller,
a `401` is `{"error":"API key required"}` from `SecurityConfig` and `{"error":"missing or invalid API
key"}` from the filter; the sweep's twenty incidents are twenty GETs; `docs/27 §27.4` promised `at=`
and versioned paths and neither exists.

**The proposal.** `/api/v1/…`, with an OpenAPI 3.1 document generated by springdoc-openapi (the 3.x
line for Boot 4) from typed request and response records, RFC 9457 problem details everywhere, ETags
that work because the volatile fields leave the body, real `Cache-Control`, a batch read, `at=`,
`fields=`, a cells layer, and the old `/api/weather` kept one release as a translating alias.

*Routes.*

| Route | Answers |
|---|---|
| `GET /api/v1/readings?lat&lon[&at][&fields][&elevationM]` | one point's reading |
| `POST /api/v1/readings:batch` | up to 100 points in one call; each answered or refused individually |
| `GET /api/v1/cells?bbox=&parameter=&at=&format=geojson` | the grid as polygons with one parameter's value, for maps |
| `GET /api/v1/cells/{cellId}` and `/series?from&to&step=hour\|day` | one cell, and its history |
| `GET /api/v1/fire/ratings[?district]` | the official rating per district, four days (§6.3.4) |
| `POST /api/v1/fire/behaviour` | FBI and GFDI from weather at a point plus fuel inputs the caller supplies (§6.3.4) |
| `GET /api/v1/observations?lat&lon[&radiusKm]` | the nearest stations' latest observations (§6.3.5) |
| `GET /api/v1/warnings?lat&lon` or `?bbox` | current warnings covering the point (§6.3.5) |
| `GET /api/v1/providers`, `/providers/{id}/spend?from&to` | the quota view (§6.3.6) |
| `GET /api/v1/status` | grid coverage and freshness per tier, last refresh, what is owed |
| `GET /api/v1/openapi.json`, `/api/v1/schemas/reading.json` | the contract, for §6.3.9 |
| `GET /api/weather…` | **alias for one release**: translates to v1 and answers today's shape |

*Headers instead of `generatedAt`.* `Date` is the request time (HTTP has always had it).
`Last-Modified` is the run's `fetchedAt`. `ETag` is a strong hash of the canonical body, which no
longer contains the request time, so two reads between refreshes match and the second is a `304`.
`Cache-Control: private, max-age=<seconds until nextRefreshAt>, stale-while-revalidate=300`.
`Weather-Next-Refresh` for humans. `Content-Encoding: gzip` on (`server.compression.enabled`, off
today).

*Problem details.* `application/problem+json`:

```json
{"type": "https://weather.example/problems/outside-range", "title": "Coordinates out of range",
 "status": 400, "detail": "lat must be -90..90 and lon must be -180..180", "instance": "/api/v1/readings",
 "errors": [{"pointer": "lat", "message": "was 135.0"}]}
```

One handler (`@RestControllerAdvice`), one shape, also for `401`, `403`, `404`, `429` (with
`Retry-After` and the `RateLimit-*` headers), `503` while warming.

*The reading.* Field names keep their units in the name, as today (`temperatureC`, `windSpeedKmh`,
`kbdiMm`) — rename nothing the Hub reads. The blocks are the same five plus what the later proposals
add, each nullable with a `reason` sibling when absent:

```json
{
  "point": {"lat": -35.02, "lon": 138.73, "elevationM": 412.0},
  "at": "2026-09-18T05:00:00Z",
  "provenance": { "…as §6.3.1…" },
  "current":  {"at": "2026-09-18T05:00:00Z", "temperatureC": 18.4, "apparentTemperatureC": 17.1, "dewPointC": 9.2,
               "humidityPct": 55, "windSpeedKmh": 22.0, "windDirectionDeg": 315, "windGustKmh": 38.0,
               "precipitationMm": 0.0, "precipitationProbabilityPct": 5, "pressureMslHpa": 1014.2, "cloudCoverPct": 40,
               "uvIndex": 4.1, "condition": "PARTLY_CLOUDY", "vapourPressureDeficitKpa": 0.9, "soilMoistureSurface": 0.21},
  "fire":     {"ffdi": 11.2, "ffdiRating": "LOW-MODERATE", "peakFfdi": 24.0, "droughtFactor": 7.4, "kbdiMm": 96.0, "kbdiBand": "MODERATE",
               "estimated": false, "basis": "integrated from 365 days of daily rain and maximum temperature",
               "afdrs": {"fbi": null, "rating": null, "reason": "fuel inputs not supplied; POST /api/v1/fire/behaviour"},
               "published": {"district": "MOUNT LOFTY RANGES", "rating": "Moderate", "fbi": 14, "totalFireBan": false, "date": "2026-09-18",
                             "source": "Bureau of Meteorology via the CFS GeoHub", "fetchedAt": "2026-09-18T04:00:00Z"}},
  "flood":    {"rain1dMm": 0.4, "rain7dMm": 12.0, "forecastRain24hMm": 0.0, "forecastRain72hMm": 3.2,
               "soilMoistureSurface": 0.21, "soilMoistureRootZone": 0.28,
               "river": {"dischargeCumecs": 12.0, "meanCumecs": 20.0, "ratioToMean": 0.6, "trend": "STEADY", "glofasCell": "g:-701:2775"}},
  "drought":  {"kbdiMm": 96.0, "kbdiBand": "MODERATE", "droughtFactor": 7.4, "meanAnnualRainfallMm": 612.0, "stepDays": 365, "basis": "…"},
  "observation": {"stationId": "94675", "name": "Adelaide (West Terrace)", "distanceKm": 6.2, "observedAt": "2026-09-18T04:50:00Z", "ageMinutes": 12,
                  "temperatureC": 19.1, "humidityPct": 52, "windSpeedKmh": 28.0, "windGustKmh": 41.0, "windDirectionDeg": 320},
  "warnings": [{"id": "IDS21035", "kind": "FIRE_WEATHER", "title": "Fire Weather Warning for Mount Lofty Ranges", "issuedAt": "…", "expiresAt": "…", "url": "…"}],
  "forecast": {"days": [ "…as today, whole days…" ], "hours": [ "…" ], "windChange": null},
  "disclaimer": "…"
}
```

`fields=current,fire` trims to the named blocks (the Hub's sweep wants `current,fire,flood,forecast`;
a map wants `current`). `provenance` and `point` are always present.

*Batch.*

```http
POST /api/v1/readings:batch
{"points": [{"id": "inc-9f3", "lat": -35.02, "lon": 138.73, "at": "2026-09-18T03:10:00Z"},
            {"id": "inc-a11", "lat": -34.55, "lon": 138.90}],
 "fields": ["current", "fire", "flood", "forecast"]}
```

```json
{"readings": [{"id": "inc-9f3", "reading": { "…" }},
              {"id": "inc-a11", "problem": {"type": "…/warming", "status": 503, "detail": "cell f:-231:926 has no run yet"}}]}
```

Partial success is the point: one refused point does not fail the sweep.

*The cells layer.* `GET /api/v1/cells?bbox=137.5,-36,140,-34&parameter=ffdi&at=now&format=geojson`
returns each cell as a polygon with `{cellId, value, band, runFetchedAt}`; pre-rendered per refresh
per parameter for the whole state and cached (§6.3.14), so a map on a sixty-second timer costs a
memory read and usually a `304`. This replaces `coverage.geojson`: the coverage of a grid is the grid.

**What the Hub changes.** `HttpWeatherClient`: base path `/api/v1`, `at(lat, lon, at)`,
`batch(List<Point>)`, `If-None-Match` on status and cells, a problem+json reader. `WeatherReading`:
either stays a map bag (D-249 says the reading is stored as it arrived, and that still holds) with
its accessors pointed at the same names, or becomes records generated from `reading.schema.json`
(§6.3.9) — the second is better and is a Hub-side choice. `WeatherManager.sweep`: one batch call per
tick for the owed plus the due, `at` set for the owed. `UsageService` (`spentSince`, `spentDaily`):
the new spend path. `WeatherLayer` (Hub): pass through `cells` instead of `coverage`, and the console
map's weather switch legend becomes "FFDI by cell" rather than four rings. FireBuddy, reading
`/api/weather` off the Hub, is untouched while the Hub's pass-through keeps its shape.

**What gets deleted.** `web/ApiCachingConfig.java`, `generatedAt` from every body, the three error
shapes, `api/WeatherLayer.java` in its current form (an assembler of typed records replaces it),
`WeatherJson`'s map builders (the records serialise themselves), the `spend` routes after the alias
release, `coverage.geojson` after the Hub's map switch moves.

**Risks.** Renaming nothing the Hub reads is the discipline; the contract test (§6.3.9) enforces it.
springdoc on Boot 4 is young — pin the version and generate the document in CI so drift is a red
build. The alias must be tested against the same fixture the Hub's test uses.

**Overrides.** The "field names are the contract, do not rename them" of 02 stands; the *shape
around* them (headers, errors, versioning) is what changes. docs/27 §27.4's `?at=` and `/api/v1`
are delivered rather than overridden.

### 6.3.4 · AFDRS / Fire Behaviour Index alongside McArthur FFDI

**The problem today.** `FireDanger` computes McArthur Mk5 FFDI and rates it on the pre-2022 bands;
`FireDanger`'s Javadoc says plainly it is "not the AFDRS … where a published rating exists it wins".
The published rating lives in the Hub (`FireDangerRatingsSource` reads the CFS GeoHub layer
`South_Australia_Fire_Danger_Ratings_Read` every two hours; `FireDangerDayEntity` keeps every
district-day for ever under D-225; `MetricsManager.published` attaches it). The grassland index is the
Hub's too (`core/fuel/GrassFireDanger`, `MetricsManager.grass`), because curing and fuel load are not
weather (Hub docs/09 §9.4). So the one consumer that needs fire danger assembles it from two services
and two implementations of 1980 arithmetic, and neither produces the number the public are told.

**What the AFDRS is.** Since 1 September 2022 the Bureau rates fire danger through the Australian
Fire Danger Rating System: a fuel-type-specific fire behaviour model gives a rate of spread and
intensity for the day's weather, mapped onto a **Fire Behaviour Index** (0 upward) with four public
ratings — Moderate (12–23), High (24–49), Extreme (50–99), Catastrophic (100+) — and "No rating" below
12. The fuel models are: grassland (CSIRO, Cheney et al. 1998: curing, grass condition, dead fuel
moisture from temperature and humidity, wind), dry eucalypt forest (Vesta Mk2: surface, near-surface,
elevated and bark fuel hazard from fuel age, plus wind, moisture and slope), mallee-heath (Cruz et
al. 2013: fuel cover and age), spinifex (Burrows et al. 2018), shrubland (Anderson et al. 2015),
pine (Cruz et al. 2008), and non-combustible. The Bureau runs them on a 1.5 km grid with national fuel
type, fuel age and curing layers, and the district rating is the worst of the grid over the district.

**What is computable here, and with what.**

| | Inputs beyond weather | Who has them | Verdict |
|---|---|---|---|
| **Grass FBI** (CSIRO grassland model) | curing %, grass condition (natural / grazed / eaten-out) | curing: the Hub's `GrassCuringEntity`, entered weekly from the CFS map; condition: a default of natural, overridable | **Computable now**, given the inputs on the request |
| **Grass GFDI** (McArthur Mk5, what the Hub computes today) | curing %, fuel load t/ha | the Hub's registers | Computable; the Hub's `GrassFireDanger` moves here |
| **Forest FBI** (Vesta Mk2) | fuel hazard scores or fuel age, fuel height, slope | fuel age partly from the Hub's `FireRegister.lastBurnAt` (CFS hazard-reduction polygons), hazard-score tables from the Vesta publications; slope from the Hub's terrain | **Computable approximately**, and it is an XL piece of work to do faithfully — the Bureau's technical notes give each model and its FBI mapping, and there are open Python reference implementations from the fire-science community to check against; none in Java that we know of |
| Mallee-heath, spinifex, shrubland, pine | fuel cover, time since fire, height | not held anywhere in this fleet | **Stays FFDI** with `afdrs.reason` saying which fuel and why |
| **The official rating and FBI per district** | none | the CFS GeoHub layer, public, anonymous | **Fetch it here too** (15 districts × 4 days, every two hours, one call) and serve it on every reading as `fire.published` |
| Fuel type at a point | | the Hub's `FuelTypeService` (CFS GeoHub, per 500 m cell, cached a month) | The service can read the same layer, or take `fuelType` on the request. Take it on the request first; read the layer when a second consumer needs it |

**The proposal.** Three parts, in order of value per effort:

1. **`fire.published`** on every reading: the district's official rating, FBI and total fire ban for
   today and the days ahead, with the fetch time. Fetched here from the same public layer the Hub
   reads; the Hub's own ledger of district-days (D-225) stays in the Hub because it is *history the
   Hub keeps for incidents*, and `MetricsManager.published` can later read this block instead of the
   register.
2. **`POST /api/v1/fire/behaviour`**, stateless: weather at a point (or a supplied reading) plus fuel
   inputs in, indices out — McArthur FFDI, McArthur GFDI, CSIRO grass FBI and its AFDRS rating, and
   forest FBI when a fuel age is given. One implementation of every fire-weather formula, in one
   `science` package, tested against the published worked examples.

   ```json
   POST /api/v1/fire/behaviour
   {"lat": -35.02, "lon": 138.73, "at": "2026-09-18T05:00:00Z",
    "fuel": {"type": "GRASSLAND", "curingPct": 85, "condition": "GRAZED", "loadTHa": 3.0, "ageYears": null, "slopeDeg": 4.0}}
   ```
   ```json
   {"weather": {"temperatureC": 31.0, "humidityPct": 18, "windSpeedKmh": 35.0, "droughtFactor": 9.1},
    "mcarthur": {"ffdi": 41.2, "ffdiRating": "VERY HIGH", "gfdi": 22.4, "gfdiRating": "HIGH"},
    "afdrs": {"model": "grassland", "fbi": 31, "rating": "HIGH", "rateOfSpreadKmh": 4.1, "intensityKwm": 3100,
              "fuelMoisturePct": 5.8, "basis": "CSIRO grassland (Cheney, Gould and Catchpole 1998), curing 85%, grazed"},
    "hours": [{"at": "…", "ffdi": 30.1, "fbi": 24, "rating": "HIGH"}, "…"]}
   ```
3. **Forest FBI**, later, once the grass model is verified against the Bureau's own numbers for a
   district on a bad day (the published FBI is the check).

**What the Hub changes.** Additive. `MetricsManager.grass` calls `/api/v1/fire/behaviour` with the
curing and load it has instead of `GrassFireDanger.of`, and reads `afdrs` beside `gfdi`; `published`
reads `fire.published`. `core/fuel/GrassFireDanger` and its test move to this repository (the
formulas, not the registers). Nothing about *when* or *for which incident* changes.

**What gets deleted.** Nothing here; on the Hub, `GrassFireDanger` once the endpoint is trusted.

**Risks.** Getting a fuel model wrong is a confident number about a fire. Every model ships with its
worked example as a test, `afdrs.basis` names the model and inputs, and the published district
rating sits beside it on every reading so a disagreement is visible. The AFDRS ratings have a
different meaning from the FFDI bands (a High FBI is not a Very High FFDI); the API never maps one to
the other.

**Overrides.** Hub docs/09 §9.4's line (the inputs are not weather; the calculation is), D-187's
placement of the grassland meter.

### 6.3.5 · Observations, warnings, and the sources beyond forecasts

**The problem today.** Every number this service serves is a model output. There is no observed
wind, temperature or humidity anywhere on the API, and no warning. The Hub *had* the Bureau's
observation product — `IDS60920.xml`, 82 South Australian stations, every ten minutes, over anonymous
FTP — and withdrew it under D-141 (FEEDS §9) because polling it for weather nobody had asked about was
waste in a system that fetched on demand. The Bureau's JSON at `api.weather.bom.gov.au` is undocumented
and its terms forbid use without permission (FEEDS §13); the Hub does not touch it, and neither will
this. The CFS GeoHub's `BOM_Flood_Watch_and_Warning` layer is token-gated (FEEDS §15.2).

**The proposal.** A service with a grid and a clock is exactly the thing that should poll the
Bureau's public products. Add, each as its own ingest with its own quota and breaker:

| Source | What | Cadence | Licence and terms | Endpoint on this API |
|---|---|---|---|---|
| **BoM AWS observations**, `IDS60920.xml` on `ftp.bom.gov.au/anon/gen/fwo/` | 82 stations, ten-minutely: temperature, dew point, humidity, wind, gust, rain since 09:00, pressure | every 10 min, conditional on the file's modified time | Bureau copyright. The anonymous FTP is the Bureau's published channel and its catalogue page states the terms: attribution, no on-selling; the free **Bureau Data Licence** (registration) is the clean footing for a service that serves the data on. An identifying User-Agent with a contact — which resurrects `weather.contact` with a reader | `observation` block on every reading (nearest station, distance, age); `GET /api/v1/observations` |
| **BoM warnings**, the state warnings index and each warning product under `anon/gen/fwo/` | Fire Weather Warnings, Severe Thunderstorm and Severe Weather Warnings, Flood Watches and Warnings, Heatwave, with their areas as district names | every 5 min, conditional GET | as above | `warnings[]` on every reading whose district a warning names; `GET /api/v1/warnings` |
| **CFS GeoHub `Total_Fire_Bans`** and `South_Australia_Fire_Danger_Ratings_Read` | the declared bans and the official ratings (§6.3.4) | every 2 h | public ArcGIS layer, anonymous, attribution to CFS/DEW; the Hub already reads both | `fire.published` |
| **BoM radar and satellite imagery** | Buckland Park (IDR64) and the regional radars; Himawari via the Bureau or via the AWS Open Data Himawari bucket (JMA terms, free with attribution) | link, never proxy | radar imagery is explicitly not for reproduction; the console links to the Bureau's loop pages | console only (§6.3.8) |
| **Lightning** | — | — | **No free feed exists that a service may use**: Blitzortung's terms forbid API use outside the volunteer network, the Bureau's lightning data is a commercial product (GPATS). `cape` and `liftedIndex` on the reading are the honest proxy | none; say so in the docs |
| **BoM flood warnings via the GeoHub layer** | | | token-gated (`HUB_ARCGIS_TOKEN` on the Hub); the FTP warnings above carry the same watches and warnings as text | covered by warnings |
| MET Norway | a second global model, 9 km | | CC BY 4.0, needs an identifying User-Agent | **not added**: it brings nothing over Open-Meteo for South Australia; delete its traces |

*The observation block.* The nearest station to the point within a radius (default 50 km), with the
distance and the age, and `null` with a reason when the nearest is too far or too old. A reading then
shows the model beside the instrument: `current.windSpeedKmh: 22` and `observation.windSpeedKmh: 38`
is exactly what a crew needs to see side by side. Not blended, not corrected — disclosed.

*Warnings.* Each warning is parsed for its kind, its districts, its issue and expiry, and stored as a
row with its text; a reading carries the warnings whose districts contain the point's district. The
Hub can attach the list to an incident as a new enrichment or read it off the component.

**What the Hub changes.** Additive. An `observation` and `warnings` on the reading the Hub already
stores whole; `WeatherPanel` gains two rows; an optional `WARNINGS` enrichment.

**What gets deleted.** Nothing. The `HttpFetcher` conditional-GET half (`etag`, `lastModified`,
`Fetched.notModified`) finally gets a caller — the warnings poll.

**Risks.** The Bureau's platform upgrade suspended open-data delivery in September 2026
(`OpenMeteoProvider.BOM`'s Javadoc); the FTP products may be affected too, so the first task is to
verify each product is being served and the ingest degrades to `null` with a reason when it is not.
FTP from a container needs passive mode and an outbound rule. Station metadata (name, position) comes
in the product; keep a `station` table and never hard-code the list.

**Overrides.** Hub D-141, for observations and warnings only.

### 6.3.6 · Providers and quotas

**The problem today.** `WeatherBudget` (258 lines) rebuilds a month of `weather_call` rows into
memory at boot and scans the list for every window on every check; `WeatherGovernor` reads the same
ledger by calendar day; `/status` shows trailing windows — two definitions of "spent today".
`HostLimiter` and `HostBudgets` (a resilience4j rate limiter per host, one permit a second) exist to
space single-point requests that the grid replaces with fifteen batched ones. Failure handling is a
cooldown on the budget (`WeatherBudget.Failure`), not a breaker. The Google provider costs three
billed calls per point and could never feed a grid; as a fallback for a point it spends money to
avoid serving the last run, which is the better answer.

**The proposal.** Per provider: a **quota** (the published limits, guarded at 90 %, counted from
`forecast_run` and `provider_call` in the database so a second instance counts the same numbers), a
**circuit breaker** (resilience4j, already a dependency for its rate limiter: open after three
consecutive failures, half-open after the cooldown the refusal names — `cooldownFor` survives as the
breaker's wait-duration function), and a **rate limiter** sized to the per-minute limit rather than
one call a second. The refresh planner asks the quota "may I spend N units now" before a tier
refresh and defers the tier (logged, on the console) rather than the governor stretching a radius.

Keep Open-Meteo, including `open-meteo-bom` behind config for the day the Bureau restores it (the
all-null rejection stays). **Delete Google**, or keep it behind `weather.providers.google.enabled`
for the console's forced probe only; it never feeds the grid. The fallback when Open-Meteo is down is
the grid's last run, served `stale` with its age, for up to 24 hours, then `null` with a reason.

**Shapes.** `GET /api/v1/providers`:

```json
{"providers": [{"id": "open-meteo", "host": "api.open-meteo.com", "licence": "CC BY 4.0 (non-commercial free tier)",
   "quota": {"perMinute": 600, "perHour": 5000, "perDay": 10000, "perMonth": 300000, "guard": 0.9},
   "spent": {"today": 4120.0, "thisHour": 220.0, "thisMonth": 61400.0},
   "breaker": {"state": "CLOSED", "failures": 0, "openedAt": null, "halfOpenAt": null},
   "lastCall": {"at": "…", "ok": true, "latencyMs": 812, "units": 1500}}]}
```

**What the Hub changes.** Nothing; `UsageService` moves to the new spend path with §6.3.3.

**What gets deleted.** `WeatherGovernor`, `WeatherTuning`, `WeatherGovernorTest`, `HostLimiter`,
`HostBudgets`, `WeatherHostBudgets`, `WeatherBudget` (a 120-line `Quotas` over SQL replaces it),
`WeatherStatus` (a typed `ProviderStatus`), `WeatherProperties.Governor`, `GoogleWeatherProvider`
(234 lines, no test) unless gated.

**Risks.** A DB-authoritative quota is one query per refresh, not per read — fine. The breaker must
not open on a single 429 whose body names a window: that is a quota fact, and the quota handles it.

**Overrides.** W-2 (Google), W-3, the governor (Hub docs/09 §9.2.1).

### 6.3.7 · Drought and flood per cell

**The problem today.** `DroughtService` spins up a year of daily rain and maximum temperature for a
50 km cell on the first fire-relevant lookup near it — two calls, one of them six units — and
re-spins the cell every day (`nearest` filters on `computedFor == today`), so a cell that stays busy
costs seven units a day for ever and a cell that goes quiet is swept and rebuilt from nothing later.
`FloodService` keeps GloFAS discharge per 5 km cell for a day, with the same rebuild. The
"dump and restore, do not re-derive" warning (04 §4.1) exists because the design makes the spin-up
the most expensive number in the system.

**The proposal.** The grid already fetches every cell's daily rain and maximum temperature four
times a day. So:

- **KBDI per grid cell, stepped daily from the grid's own `cell_day`**, persisted as
  `cell_day.kbdi_mm` and `drought_factor`. One spin-up per cell, once, from the archive
  (1,500 cells × 6 units ≈ 9,000 units, run across three quiet nights or on the paid tier), then zero
  calls for ever: each local midnight the stepper takes yesterday's `tmax_c` and `rain_mm` (from the
  forecast until the archive confirms the day about five days later, then corrected — `source`
  says which) and runs `Kbdi.step`. The forecast outlook carries it forward exactly as
  `FireDanger.outlook` does today. Mean annual rainfall comes from the cell's own `cell_day` history
  once there is a year of it, and from the archive spin-up until then.
- **GloFAS discharge keyed on GloFAS's own 0.05° cell** (`discharge_day`), because a river is a
  line and a 0.15° weather cell contains several: the reading maps the point to the GloFAS cell it
  falls in, and the flood block says which. Fetched on demand the first time a GloFAS cell is asked
  about, then refreshed daily while it keeps being asked about (a "recently asked" set, 30 days),
  92 days back plus 7 ahead in one call as today. ≤ 200 units a day at the numbers in §6.3.1.
- **Open-Meteo's soil moisture** (`soil_moisture_0_to_1cm` in the core set, `27_to_81cm` in the
  extras) stays on the reading as the model's own view of saturation, beside the KBDI — a simpler
  quantity that needs no spin-up, and the two disagreeing is information. Not a replacement: the
  drought factor McArthur needs is a KBDI function.

**Shapes.** `cell_day` and `discharge_day` in §6.3.2; the `drought` and `flood.river` blocks in
§6.3.3.

**What the Hub changes.** Nothing.

**What gets deleted.** `DroughtService` (289 lines), `FloodService` (274), `DroughtCellEntity`,
`RiverCellEntity`, `WeatherProperties.Drought` and `.Flood` bar the endpoints and the archive lag,
the two cell tables (dropped by hand), the "do not re-derive" rule. `Kbdi`, `DroughtIndex`,
`FloodWeather`, `FloodOutlook`, `OpenMeteoDailyClient` (as a `science` stepper and an ingest client)
stay.

**Risks.** The stepper is a daily job that must run exactly once per cell per local day; make it
idempotent on `(cell_id, day)` and reconcile from the archive when it confirms. The archive's own
resolution (ERA5-Land 0.1°, ERA5 0.25°) is coarser than the fine tier; a spin-up from it is what the
current design does too.

**Overrides.** 04 §4.1's migration rule for `drought_cell`; the drought cell radius, the river cell
radius and their governor bands.

### 6.3.8 · Console: a real map page, provider health and spend charts, a point inspector

**The problem today.** §6.1 item 7. `weather.html` is a single page of tables; `weather-map.js` is
dormant; the only chart is a four-pixel progress bar per provider.

**The proposal.** Three pages on the existing Bootstrap, htmx and Leaflet chrome (`layout.html`,
`map.js`, `theme.js` stay):

1. **Map** (`/console/map`): the cells layer from `GET /api/v1/cells?parameter=` coloured by FFDI
   rating (the published AFDRS palette for `fire.published` and the McArthur bands for `ffdi`, never
   mixed on one layer), a parameter switch (temperature, humidity, wind, gust, FFDI, KBDI, soil
   moisture, published rating), the observation stations as pins with age, warning polygons by
   district, a timeline scrubber over `at=` (the point of history is to look at it), and a click that
   opens the **point inspector** — the whole reading for the point as the API serves it, with the
   raw JSON one click away. Radar and satellite links to the Bureau's pages beside the map, not in it.
   `weather-map.js` is rewritten as `cells-map.js` against the new layer, or deleted with it; the
   Hub's own copy is the Hub's to keep.
2. **Providers** (`/console/providers`): per provider, spend per hour for the last 48 hours and per
   day for the month as bars against the guard (Chart.js from webjars, one chart component), the
   breaker state and its history, refresh tier timings and their latencies, the last twenty calls.
3. **Grid** (`/console/grid`): coverage and freshness per tier, the cells with the oldest run, the
   drought stepper's last day, partition and retention state, disk per table.

The probe form stays as the inspector's address bar; the "force a fresh call" button spends units
against the quota and says so.

**What the Hub changes.** None. When `coverage.geojson` retires, the Hub's `WeatherLayer.spec()`
points at `cells` and its legend changes — a ten-line change on the Hub.

**What gets deleted.** `static/js/weather-map.js`, the anchors, drought cells and river cells tables
in `weather.html`, `/console/weather/sweep`.

**Risks.** A map page that polls must never spend: the cells layer is served from the pre-rendered
snapshot, and the inspector reads the grid; only the force button calls out.

**Overrides.** None.

### 6.3.9 · Contract tests shared with the Hub

**The problem today.** The only test of the wire shape is `WeatherApiControllerTest`, which asserts
the `status` body's keys; the reading's shape is asserted nowhere on this side and read by string on
the Hub's (`WeatherReading`, `WeatherPanelTest`'s hand-written map). A field renamed here is a field
quietly missing there, as the test's own comment says.

**The proposal.** One JSON Schema for the reading, `contracts/reading.schema.json` (draft 2020-12),
checked into **both** repositories at the same path, with a version in `$id`, and tested on both
sides:

- Here: a test builds a reading from fixtures through the real assembler, serialises it and validates
  against the schema (`networknt/json-schema-validator`); a second test validates the schema against
  the generated OpenAPI's `Reading` component so the two cannot drift; a third replays the alias
  `/api/weather` and validates against the legacy fixture the Hub's test uses today.
- On the Hub: `WeatherReadingContractTest` validates the recorded fixture `weather-reading.json`
  against the same schema and runs every `WeatherReading` accessor over it, so a schema change breaks
  the Hub's build before the deployment does.
- A GitHub Action on the Hub fetches `contracts/reading.schema.json` from this repository's tagged
  release and fails if it differs from the checked-in copy — the copy is deliberate, the drift is not.

Later, `schemathesis` (Python) or `openapi-request-validator` against the running service as a
smoke test in CI, and the same schema as the source for generated Hub-side records.

**What the Hub changes.** Additive: one test, one fixture, one action.

**What gets deleted.** Nothing.

**Risks.** None worth naming; this is the cheapest proposal with the best ratio.

**Overrides.** None.

### 6.3.10 · Configuration: fewer knobs, none dead

**The problem today.** Four prefixes and four records (03) describe 50-odd keys, of which three are
read by nothing (`weather.timeout`, `weather.contact`, `weather.cache.sweep-interval`), thirteen tune
the governor, seven the cache, and one (`weather.terrain.enabled`) switches on a term whose weight is
zero. The record split (`weather` and `weather.app`) exists to dodge a Boot binding rule.

**The proposal.** One record per feature that a deployment genuinely varies, everything else a
constant next to the code that knows why (the `Spec` pattern, extended):

```yaml
weather:
  grid:      { fine-size-deg: 0.15, coarse-size-deg: 0.5, refresh: { core: 6h, hot: 1h, extras: 24h }, hot-cells-max: 60 }
  providers: { open-meteo: { enabled: true, paid: false }, open-meteo-bom: { enabled: false }, google: { enabled: false } }
  sources:   { bom-observations: { enabled: true, every: 10m }, bom-warnings: { enabled: true, every: 5m }, cfs-ratings: { enabled: true, every: 2h } }
  retention: { forecasts: 14d, hours: 730d, observations: 730d }
  contact:   ${WEATHER_CONTACT}            # in the User-Agent, read by HttpFetcher, required
  app:       { console: { code: ${WEATHER_CONSOLE_CODE} }, api: { cors-origins: ${WEATHER_CORS_ORIGINS:}, requests-per-minute-per-key: 600 } }
```

Every key has a `@DefaultValue` on its record (D-147 stands); the yml names only the environment
ones. `WEATHER_PORT` and `WEATHER_DB_PORT` go into `.env.example`. A startup log line prints every
effective value once (D-147's readable answer, live).

**What the Hub changes.** Nothing.

**What gets deleted.** `WeatherProperties.Cache`, `.Governor`, `.Refresh`, `.Fire.fallbackDroughtFactor`
and `.basis` (a reading without a KBDI has `fire: null` with a reason; an assumed drought factor is
the confident-number-about-nothing the code elsewhere refuses), `.Drought` and `.Flood` radii,
`weather.timeout`, `weather.cache.sweep-interval`, `TerrainProperties`, `weather.forecast-days`
and `-hours` (the grid's spans are constants of the tier).

**Risks.** None.

**Overrides.** None; D-147 kept.

### 6.3.11 · Security and operations

**Keys and scopes.** `ApiKeyEntity.scope` exists and is always `ALL` (D-122). Give it meaning:
`read:readings`, `read:cells`, `read:diagnostics`, `write:diagnostics` (the clears), `admin`
(keys). A key for a map consumer gets `read:cells` only. The filter checks the route's required
scope; the console issues keys with a scope picker.

**Rate limits per key.** Today's 600 a minute is an in-memory resilience4j limiter per key
(`ApiKeyAuthenticationFilter`), reset by a restart and per instance. Keep it in memory (a shared
limiter is not worth a Redis) but expose it: `RateLimit-Limit`, `RateLimit-Remaining`,
`RateLimit-Reset` on every response and `Retry-After` on a `429`, per the IETF draft. A per-key
*daily* quota as a column, for a consumer that should not be able to walk the whole grid every
minute.

**CORS.** As today: an allowlist, never `*`, `GET` and `OPTIONS` — plus `POST` for the batch route.

**Secrets.** `.env` only (D-101), as today. Nothing new is secret except the Bureau's Data Licence
registration if one is taken.

**Readiness that includes the database.** `management.endpoint.health.group.readiness.include:
readinessState, db, grid` — `db` is Spring's `DataSourceHealthIndicator`, `grid` a
`HealthIndicator` that is `UP` when the fine tier is ≥ 90 % covered by a run younger than two refresh
intervals, `OUT_OF_SERVICE` while warming. The compose healthcheck already targets readiness; it will
now mean something.

**Metrics per provider and per endpoint.** Micrometer, on `/actuator/prometheus` (already exposed):
`weather_upstream_calls_total{provider,tier,outcome}`, `weather_upstream_units_total{provider}`,
`weather_upstream_latency_seconds{provider}`, `weather_quota_remaining{provider,window}`,
`weather_breaker_state{provider}`, `weather_grid_run_age_seconds{tier}`,
`weather_grid_coverage_ratio{tier}`, `weather_api_requests_seconds{route,status}` (Spring's
`http.server.requests` renamed by tag), `weather_store_bytes{table}`. The console's charts read the
same registry.

**Structured logs.** Spring Boot's own: `logging.structured.format.console: ecs` in production and
the human pattern under a `dev` profile — one line of yml. `LogCapture` keeps working: it sits on
Logback's root logger above the encoder.

**Access log.** Batch `api_access_log` writes (the drain pattern `LogEventStore` already has) or
sample them; a synchronous insert per request in the filter is the one write on every read path.

**The Docker image.** The Dockerfile builds on `maven:3-eclipse-temurin-26` and runs on
`eclipse-temurin:25-jre-alpine` — about 200 MB. Two cheap wins: Spring Boot's CDS/AOT
(`spring-boot:process-aot` plus a training run to produce the archive) takes a boot from about six
seconds to about two; a `jlink`ed runtime with only the modules used halves the image. Memory: with
`-XX:MaxRAMPercentage=75` and the grid snapshot at about 10 MB, a 512 MB container is comfortable;
set the limit in compose so the JVM sizes to it. A Python image (§6.3.12) would be
`python:3.13-slim`, about 150 MB, about 120 MB resident.

**Startup time.** Rehydrate is 1,500 rows from `cell_latest` (§6.3.14) — under a second. The refresh
scheduler starts after readiness, not before, so a boot is never blocked on Open-Meteo.

**Backups of the time series.** A nightly `pg_dump --format=custom` of the `weather` database,
excluding `cell_forecast` (re-fetchable within its 14-day life), to the host and off it — under a
gigabyte for two years at §6.3.2's numbers. `api_key` is the only table that cannot be regenerated.

**What a second instance would need.** (a) The quota counted from the database, not memory
(§6.3.6) — done by that proposal. (b) One refresh scheduler at a time: ShedLock over a Postgres table,
or `pg_try_advisory_lock` per job; the instance that does not hold the lock still serves. (c) No
in-memory state that matters: the cell snapshot is a cache rebuilt from the store on boot and on a
`NOTIFY` from the instance that refreshed. (d) The per-key limiter stays per instance and the
documented limit is per instance. That is the whole list; nothing else in this design is stateful.

**What the Hub changes.** Nothing.

**Overrides.** None.

### 6.3.12 · Language: Python or Java, decided on evidence

James: *"I'm even considering changing Weather to use Python to make use of a larger set of imports
and tools at our disposal."* This section takes that seriously, scores it, and recommends.

**What Python offers, concretely.**

| Need | Python | Java (what exists or would be written) |
|---|---|---|
| Typed models and an OpenAPI document | FastAPI + Pydantic v2: the models *are* the schema; `/openapi.json` for free; JSON Schema export for §6.3.9 with one call | Spring + springdoc-openapi 3.x: records with Bean Validation, the document generated from them; JSON Schema via `victools/jsonschema-generator`. Equivalent, a little more ceremony |
| Gridded and time-series work | xarray, numpy, pandas: a 1,500 × 72 × 20 forecast is one `DataArray`; resampling, rolling means, percentiles are one-liners | No xarray. The grid here is *tabular* — 1,500 cells × arrays — and `double[]` plus SQL (`percentile_cont`, window functions over `cell_day`) covers every operation §6.3.2 names. The cost of "no xarray" for this design is close to zero; it becomes real only for **raster** work |
| NWP files (GRIB2, NetCDF) | cfgrib (ecCodes) and netCDF4: reading a BoM ACCESS-C GRIB is ten lines; Herbie fetches model runs from cloud buckets; NCI's THREDDS serves ACCESS to registered NCI users | Unidata's netcdf-java reads GRIB2 and NetCDF, and is heavy and little used outside Unidata. Possible, unpleasant |
| Meteorological calculations | MetPy: dew point, wet-bulb, VPD, mixing ratio, wind components, with units | A `science` package of a dozen functions we write and test. Small |
| Fire-behaviour reference implementations | open Python implementations of the AFDRS models exist in the fire-science community to check against (§6.3.4) | None known in Java; we port from the papers and the Python |
| Geometry and DEMs | shapely, pyproj, geopandas, rasterio | JTS (have), GeoTools; no rasterio equivalent worth having. The grid needs one height per cell from an endpoint, not a DEM |
| Time-series database | SQLAlchemy 2 + asyncpg, or psycopg 3; TimescaleDB from either | Spring Data JPA (have) or plain `JdbcClient`; arrays map cleanly in either |
| Scheduler and concurrency | APScheduler on asyncio; httpx for concurrent fetches | `TaskScheduler` (have) on virtual threads; `RestClient` (have) |
| Testing | pytest, hypothesis, schemathesis against the OpenAPI, testcontainers-python | JUnit 5, AssertJ (have), Testcontainers |
| Typing and tooling | mypy or pyright, ruff, uv; typing is optional and must be enforced | the compiler; Lombok (have) |
| Runtime | uvicorn, one worker per core; ~120 MB resident; sub-second start; `python:3.13-slim` | JVM ~300–400 MB resident; ~2–6 s start; JRE alpine |
| Packaging pain | ecCodes and GDAL as C dependencies are the classic slim-image problem; `uv` and prebuilt wheels have mostly solved it | Maven, one jar |
| The fleet | a second toolchain beside two Spring Boot 4 applications: two build systems, two dependency policies, two sets of CVE feeds, two ways to do logging, security, health | one toolchain; `LogCapture`, `ApiKeyAuthenticationFilter`, `SecurityConfig`, the diagnostics layer are shared shapes with the Hub and Operations today |
| What already exists | the maths ports in an afternoon; the rest is redone anyway under §6.3.1 | 10,000 lines, of which about 4,000 are deleted by this catalogue and 3,000 are kept (security, access, diagnostics, science, json, console chrome) |

**The decision matrix.** Scores 1 (poor) to 5 (strong); weights reflect this service, this owner,
this year.

| Criterion | Weight | Java | Python | Weighted Java | Weighted Python |
|---|---|---|---|---|---|
| One toolchain with the Hub and Operations; shared platform code | 3 | 5 | 1 | 15 | 3 |
| Code that exists and is tested, and the owner's JVM operating knowledge | 3 | 5 | 2 | 15 | 6 |
| What the grid design needs (tabular arrays, SQL, HTTP JSON) | 2 | 4 | 5 | 8 | 10 |
| Gridded NWP ingestion (GRIB/NetCDF), if it ever comes | 1 | 2 | 5 | 2 | 5 |
| Fire-science reference implementations to check against | 1 | 2 | 4 | 2 | 4 |
| API, typing, OpenAPI, JSON Schema | 2 | 4 | 5 | 8 | 10 |
| Runtime footprint and start time | 1 | 3 | 5 | 3 | 5 |
| Migration cost and risk (the strangler, two services for a month) | 2 | 5 | 2 | 10 | 4 |
| Contributor pool for a small Australian fire-weather service | 1 | 3 | 4 | 3 | 4 |
| | **16** | | | **66** | **51** |

Move the NWP weight to 3 and the fire-science weight to 2 and it is 68 to 60 — still Java. Only a
future in which this service *ingests gridded model files itself* (ACCESS-C from NCI, radar rasters,
Himawari) turns the matrix, and that future is a different service: hundreds of megabytes per model
run, a data engineering problem, not an HTTP-and-JSON one.

**Recommendation.** **Stay on Java for the overhaul.** The reasons Python is attractive are real and
they are almost all about a job this service does not do yet. What it does — fetch JSON on a
schedule, keep it in Postgres, compute a few dozen scalar formulas, serve JSON — Java does as well,
and it does it with the security filter, the diagnostics layer, the console chrome and the build the
other two applications already have. When GRIB ingestion is actually on the plan, build it as a
**separate Python process** (`weather-gridder`: xarray, cfgrib, one job per model run, writing into
the same `cell_hour` and `cell_day` tables under the same schema) beside the Java API, rather than
rewriting the API service; that is the polyglot shape that uses each language for what it is for.

**If James chooses Python anyway.** It is a legitimate choice — the scores are not far apart, and
"we want the tools" is a reason — so here is what it looks like, done properly.

*Skeleton.*

```
weather/
  pyproject.toml                      # uv; ruff; mypy --strict; pytest
  src/weather/
    main.py                           # FastAPI app factory, lifespan starts the scheduler
    settings.py                       # pydantic-settings: the yml above as env/TOML
    api/v1/
      readings.py cells.py fire.py observations.py warnings.py providers.py status.py
      problems.py                     # RFC 9457 handlers        etag.py  # content hashing, Cache-Control
      legacy.py                       # /api/weather alias for one release
    models/                           # pydantic v2, exported to contracts/reading.schema.json in CI
      reading.py provenance.py conditions.py fire.py flood.py drought.py observation.py warning.py cell.py
    grid/
      cells.py                        # the two tiers, index maths, point -> cell
      tiers.py                        # hot-cell selection
      planner.py                      # what to refresh when, against the quota
    ingest/
      openmeteo.py                    # openmeteo-requests client, batched coordinates, lean/extras sets
      glofas.py elevation.py archive.py
      bom_ftp.py                      # observations and warnings, conditional on modified time
      cfs_geohub.py                   # ratings, total fire bans
    science/
      kbdi.py ffdi.py windchange.py wmo.py units.py steadman.py
      afdrs/grass.py afdrs/forest.py afdrs/fbi.py
    store/
      db.py                           # psycopg 3 pool (or asyncpg)
      migrations/                     # plain SQL, applied by a tiny runner, or alembic
      forecasts.py hours.py days.py observations.py warnings.py quotas.py snapshot.py
    scheduler/
      jobs.py                         # APScheduler AsyncIOScheduler: refresh tiers, stepper, retention, partitions
      quotas.py breaker.py            # per-provider token bucket and breaker
    console/                          # Jinja2 + htmx + Leaflet: the three pages of §6.3.8
    platform/
      auth.py                         # API keys, scopes, rate limit headers
      diagnostics.py                  # the docs/26 shape
      logging.py                      # structlog -> JSON
  tests/
    unit/                             # science (property tests with hypothesis), grid maths, etag
    contract/                         # schemathesis against the OpenAPI; the shared schema fixtures
    integration/                      # testcontainers Postgres: migrate, refresh one cell from a stubbed Open-Meteo, read ?at=
  Dockerfile                          # python:3.13-slim, uv sync --frozen, uvicorn
  compose.yaml
```

*What ports directly* (same formulas, same tests, translated): `Kbdi` (170 lines), `FireDanger` (141),
`WindChange` (117), `WmoCodes`, the `Band` tables, the field names in `WeatherJson` (as the pydantic
models), the `cooldownFor` window parsing, the `Conditions` null discipline (Optional fields, never
defaults). *What is redone*: the grid, the store, the scheduler, the providers (the official
Open-Meteo client batches coordinates and returns FlatBuffers), the console, auth and diagnostics (to
the same shapes, so the Hub's agent reads it unchanged).

*The strangler.* Stand `weather-py` up on 8083 against its own database (`weather2` on the same
Postgres), let it build its grid, run the contract tests (§6.3.9) against both services from the same
fixtures until they pass on both, point a copy of the Hub's `HUB_WEATHER_URL` at 8083 in a
development stack for a week, then switch the deployment's `HUB_WEATHER_URL`, watch the owed backlog
on `/console/services` stay empty, and retire the Java service and its database a week later. No
data moves: the grid re-warms, the drought spins up, the API keys are re-issued.

**The Hub sees none of this.** It has one `base-url` and one key (`hub.weather.base-url`,
`hub.weather.api-key`), it reads JSON by a schema, and D-242 says placement is configuration. If the
v1 contract holds, the language behind it is invisible — which is the strongest argument that the
language question can be decided on the merits above rather than on fear of the migration.

### 6.3.13 · Structure and codebase hygiene, whichever language

**Package layout by feature**, not by kind (today: `service` holds providers, the cache, the
governor, the budget, drought, flood and the entities; `core` holds maths and JSON builders; `api`
holds a controller and a 487-line assembler):

```
au.weather.grid       cells, tiers, planner, snapshot            (§6.3.1, §6.3.14)
au.weather.ingest     openmeteo, glofas, elevation, archive, bom, geohub, quotas, breaker   (§6.3.5, §6.3.6)
au.weather.store      entities, repositories, migrations, retention, partitions             (§6.3.2)
au.weather.science    kbdi, ffdi, afdrs, windchange, wmo, units, steadman                   (pure)
au.weather.api        v1 controllers, request/response records, problems, etag, legacy alias (§6.3.3)
au.weather.console    pages and their models                                                (§6.3.8)
au.weather.platform   access, security, diagnostics, config, startup                        (shared shapes with the Hub)
```

**Typed records instead of `Map<String, Object>`.** The classes that pass maps today, to be
replaced: `api/WeatherLayer` (`at`, `coverage`, `anchor`, `droughtCell`, `riverCell`, `cacheSummary`,
`providers`), `api/WeatherApiController` (`cacheBlock`, `tuningBlock`, `providerBlock`, `limits`,
`statusBody`, `spend`, `spendDaily`), `core/WeatherJson` (every method), `console/WeatherController.probe`,
`diagnostics/DiagnosticsLayer` (`summary`, `weather`, `logs`, `brief`). Each becomes a record
(`Reading`, `Provenance`, `Current`, `Fire`, `Flood`, `Drought`, `Observation`, `Warning`,
`ForecastDay`, `ForecastHour`, `ProviderStatus`, `GridStatus`, `Diagnostics`) with Jackson
serialising it and springdoc documenting it. **A mapping layer at the API edge only**: the alias
controller is the one place that turns a record into the legacy map. GeoJSON stays a map, because
GeoJSON is one.

**Sealed types for provider outcomes.**

```java
sealed interface Fetch permits Fetch.Ok, Fetch.Refused, Fetch.Failed, Fetch.Rejected {
    record Ok(List<CellSeries> cells, double units, Duration latency) implements Fetch {}
    record Refused(String window, Duration retryAfter) implements Fetch {}      // a 429 naming its window
    record Failed(String reason, boolean transientFailure) implements Fetch {}   // timeouts, 5xx
    record Rejected(String reason) implements Fetch {}                           // a 200 full of nulls
}
```

and `sealed interface Answer permits Answer.Reading, Answer.Unavailable` for the read path, so a
caller cannot forget the empty case. `switch` with pattern matching replaces the string-building in
`WeatherService.at`.

**Validation at the boundary.** Request records with Bean Validation (`@DecimalMin/-90`, `@Past`
for `at`, `@Size(max = 100)` for the batch); a `Point` value type that cannot hold a swapped pair
(the controller's comment about Kazakhstan becomes a constructor); `Instant` parameters bound by
Spring rather than parsed in three places. Everything past the controller trusts its arguments.

**Dead code and dead configuration, removed.** From §6.1 item 6: `core/Text`, `core/WeatherBands`
(unless `/api/v1/vocabulary` is wanted — then keep and serve it), `Nodes.parse`, `Numbers.round3`,
`WeatherCallRepository.findTop50ByOrderByAtDesc`, the unused two-thirds of `geo/Geo` (keep
`planarMetres`, `haversineMetres`, `metresPerDegree*`, `point`, `footprint`, `sphericalAreaM2`;
delete `hull`, `intermediate`, `destination`, `initialBearingDeg`, `normalisedSeparation`,
`spatialFeature`, `logit`, `sigmoid`, `footprintsOverlap`, `line`, `insideSouthAustraliaMargin` unless
the grid uses it), `geo/LatLon` if nothing needs it after that, the MET Norway comments in
`HttpFetcher`, `WeatherHostBudgets` and `Hourly`, `weather.timeout`, `weather.contact` (or wire it),
`weather.cache.sweep-interval`, the `spring.thymeleaf.cache: false` in the production yml.

**Consistent naming.** Every class in a service called weather is named `Weather*`:
`WeatherService`, `WeatherCache`, `WeatherLayer`, `WeatherController`, `WeatherApiController`,
`WeatherProperties`, `WeatherAppProperties`, `WeatherHostBudgets`, `WeatherSweeper`, `WeatherBudget`,
`WeatherGovernor`, `WeatherTuning`, `WeatherStatus`, `WeatherAnswer`, `WeatherReport`, `WeatherJson`,
`WeatherBands`, `WeatherApplication`. Drop the prefix everywhere but the application class:
`Grid`, `ReadingAssembler`, `ReadingsController`, `Properties`, `Quotas`. Units in field names as
today; `Km`, `Kmh`, `Mm`, `C`, `Pct`, `Deg`, `M`, `Hpa` — never a bare number.

**The test pyramid.**

| Layer | What | How |
|---|---|---|
| Unit | the science (every formula against its published worked example and by property), grid index maths, tier selection, ETag canonicalisation, the planner against a fake quota | JUnit 5, AssertJ, no Spring — as today |
| Contract | the reading against `contracts/reading.schema.json`; the OpenAPI document against the schema; the legacy alias against the Hub's fixture | `networknt/json-schema-validator`, a `MockMvc` slice |
| Integration | **one** `@SpringBootTest` with Testcontainers `postgis/postgis:18-3.6`: Flyway migrates, a stubbed Open-Meteo (WireMock) answers fifteen batched requests from a fixture, the grid warms, a point reads back, `?at=` reads yesterday, the drought stepper steps, retention drops a partition | Testcontainers, WireMock; tagged so `./mvnw verify` runs it and `-DskipITs` does not |
| Smoke | one live cell from the real Open-Meteo, nightly, off by default | a tagged test, a scheduled workflow |

**What the Hub changes.** Nothing.

**Overrides.** None; this is the shape the other proposals land in.

### 6.3.14 · Caching and optimisation, layer by layer

**HTTP.** As §6.3.3: the ETag is a strong hash of the canonical body with the request time removed
from it; `Last-Modified` is the run's `fetchedAt`; `Cache-Control: private, max-age` runs to the next
refresh with `stale-while-revalidate`; `Vary: Accept-Encoding`; gzip on. The `304` then happens: a
consumer polling `cells` every minute between six-hourly refreshes gets 359 `304`s and one body. The
Hub's client sends `If-None-Match` for `status` and `cells`, and not for readings (each is a different
point).

**In-process.** A `CellSnapshot` per cell (its latest run's arrays, its `cell_day` tail, its
elevation and district), all 1,500 in a `ConcurrentHashMap` — about 10 MB — replaced atomically per
cell by the refresh job and rebuilt from the store on boot. A point read is arithmetic to a cell id
and a map lookup; **Postgres is not on the hot path**. The cells GeoJSON per parameter is rendered
once per refresh into a byte array with its ETag, so the map's poll is a comparison. The observation
nearest-station lookup is a small in-memory KD-tree or a linear scan over 82 stations, refreshed
every ten minutes.

**The database as the durable cache and the history.** Indexes: `cell_hour (cell_id, valid_at)` is
the primary key; `forecast_run (fetched_at)`; `observation (station_id, observed_at)`;
`warning (district, expires_at)`. Partitioning by time (§6.3.2) so retention is `DROP PARTITION`, not
`DELETE`. A `cell_latest (cell_id, run_id, from_hour, …arrays…)` table upserted on refresh gives the
boot rehydrate one query and a second instance a source of truth — a plain table, not a materialised
view, because it is written by the job that knows when it changed. **PostGIS geography versus a cell
key**: the point-to-cell mapping is arithmetic and needs no spatial index; PostGIS stays for
`bbox` queries over cell polygons, for warning areas and station positions, and because the image
has it. The per-row `geometry(Point,4326)` on every anchor and cell entity goes.

**Provider request batching and coalescing.** Coordinates batched up to 100 per request (fifteen
requests per tier refresh). On-demand off-grid points and GloFAS cells go through a **single-flight**
map (`ConcurrentHashMap<Key, CompletableFuture<Fetch>>`): twenty simultaneous requests for one cold
key make one upstream call, which is the defect in `WeatherService.at` today, fixed. Refresh jobs
for different tiers never overlap (one scheduler thread per provider).

**Cold start.** Boot: rehydrate `cell_latest` (< 1 s), mark readiness `OUT_OF_SERVICE`, start the
scheduler, refresh the fine tier first (about two minutes), then readiness `UP`. A read during
warming answers `503` problem+json with `Retry-After: 60` — or, behind a switch, fetches that one
cell on demand and answers it while the rest fills. The Hub's owed backlog handles the first.

**A performance budget, and how to measure it.**

| Measure | Budget | Measured by |
|---|---|---|
| point read, from the snapshot | p50 < 5 ms, p95 < 25 ms | `weather_api_requests_seconds{route="readings"}` |
| batch of 50 points | p95 < 100 ms | same, `route="readings:batch"` |
| cells GeoJSON, whole state, one parameter | p95 < 50 ms served, < 2 s to render per refresh | `route="cells"`, and a render timer |
| upstream units per day | ≤ 8,600 (§6.3.1), alert at 80 % and 90 % of 9,000 | `weather_upstream_units_total` |
| upstream calls per day, HTTP | ≤ 150 | `weather_upstream_calls_total` |
| refresh of the fine tier, wall clock | < 3 min | a job timer |
| storage growth | ≤ 30 MB a day raw, ≤ 5 GB after two years | `weather_store_bytes{table}` from `pg_total_relation_size` |
| boot to ready, warm database | < 15 s | `StartupHistory` |
| memory, resident | < 450 MB | the container |

A `k6` or `hey` run against a warm service in CI once a week, asserting the p95s; the numbers on the
console's grid page every day.

**What the Hub changes.** Nothing; the headers are additive.

**Overrides.** None.

### 6.3.15 · The delete list

What §6.3.1, §6.3.6, §6.3.7, §6.3.10 and §6.3.13 leave behind, file by file. About 4,000 lines go;
about 2,000 come, most of them records and SQL.

| File | Lines | Why |
|---|---|---|
| `service/WeatherCache.java` | 462 | the grid (§6.3.1) |
| `service/WeatherAnchorEntity.java` | 89 | " |
| `test/…/WeatherCacheSelectionTest.java` | 162 | " |
| `service/WeatherGovernor.java` | 246 | quotas (§6.3.6) |
| `service/WeatherTuning.java` | 81 | " |
| `test/…/WeatherGovernorTest.java` | 279 | " |
| `service/WeatherBudget.java` | 258 | → `ingest/Quotas` over SQL, ~120 lines |
| `service/WeatherStatus.java` | 56 | → `ProviderStatus` record |
| `service/WeatherHostBudgets.java`, `http/HostBudgets.java`, `http/HostLimiter.java` | 190 | batched requests, resilience4j per provider |
| `service/GoogleWeatherProvider.java` | 234 | delete, or gate (§6.3.6) |
| `service/DroughtService.java`, `service/DroughtCellEntity.java` | 378 | → `grid/DroughtStepper` over `cell_day` (§6.3.7) |
| `service/FloodService.java`, `service/RiverCellEntity.java` | 344 | → `ingest/GloFasClient` + `discharge_day` |
| `service/WeatherService.java` | 459 | → `ReadingAssembler` (records) and the planner; the fire and flood assembly survives as methods |
| `api/WeatherLayer.java` | 487 | → the assembler and `CellsLayer` |
| `api/WeatherApiController.java` | 271 | → `api/v1/*Controller` and `LegacyWeatherController` |
| `core/WeatherJson.java` | 444 | records serialise themselves; the alias keeps a 100-line translator |
| `core/WeatherAnswer.java` | 45 | → `Reading` |
| `core/Text.java`, `core/WeatherBands.java` (+ `BandTest`'s half) | 80 | unused |
| `geojson/Coverage.java` | 134 | union-of-discs outlines |
| `geo/Geo.java` (two-thirds), `geo/LatLon.java` | ~250 | the Hub's matching maths |
| `terrain/ElevationService.java`, `terrain/TerrainProperties.java`, `test/…/ElevationServiceTest.java` | 296 | → a 60-line batch client in `ingest` |
| `startup/WeatherSweeper.java` | 108 | → `grid/RefreshScheduler` |
| `web/ApiCachingConfig.java` | 30 | content ETags in the API layer |
| `service/WeatherProperties.java` | 234 | → ~80 lines of `grid`, `providers`, `sources`, `retention` records |
| `service/WeatherRepositories.java` | 48 | → the store's repositories |
| `static/js/weather-map.js` | 326 | dormant; → `cells-map.js` |
| `templates/weather.html` (anchors, drought, river tables, ~200 lines) | 200 | the grid page |
| `db/migration/README.md`'s hybrid rule for the new tables | — | Flyway owns them |
| Tables `weather_anchor`, `drought_cell`, `river_cell`; column `weather_call.detail`'s role | — | dropped by hand once looked at (D-082) |

Kept, moved: `core/Kbdi`, `FireDanger`, `WindChange`, `WmoCodes`, `Hourly`, `Band`, `Numbers`,
`Ranges` (trimmed), `Conditions` (trimmed to what is fetched), `DayOutlook`, `FireWeather`,
`FloodWeather`, `DroughtIndex`, `FireOutlook`, `FloodOutlook` → `science` and the response records;
`OpenMeteoProvider.parse` and `OpenMeteoDailyClient` → `ingest/OpenMeteoClient` (batched);
`OpenMeteoBomProvider` → a second `Spec`; `json/*`, `access/*`, `security/*`, `diagnostics/*`,
`startup/ConsoleUserBootstrap`, `config/SchedulingConfiguration`, `http/HttpFetcher` (with a
User-Agent that reads `weather.contact`) and `Fetched` (with its conditional half finally used),
`templates/layout.html`, `login.html`, `api-keys.html`, `diagnostics.html`, `static/js/map.js`,
`theme.js`.

---

## 6.4 The sequence

Each phase ends in a state the Hub can run against, with the Hub work it needs — none until phase 2,
and none of it large.

### Phase 0 · Freeze the contract (two days)

- §6.3.9: write `contracts/reading.schema.json` from *today's* `/api/weather` shape; the test here,
  the test and fixture on the Hub, the action.
- §6.3.3 in outline: the v1 route list and the reading shape agreed and written into 02 as the
  target, so phase 1 is built to it.
- Decide §6.3.12 (Java; or Python, and then phase 1 is the Python skeleton built beside the Java
  service).

**Hub:** `WeatherReadingContractTest` and the fixture. **State:** unchanged service, a contract
that fails a build when broken.

### Phase 1 · The grid, the store, the quotas — behind the old contract (two to three weeks)

- §6.3.1 grid and tiers; §6.3.2 tables and Flyway; §6.3.6 quotas and breaker; §6.3.7 drought stepper
  and GloFAS by cell; §6.3.10 the new properties; §6.3.15 the deletions; §6.3.13's package layout
  and records as the code is written; §6.3.14's snapshot and single-flight.
- `/api/weather` answered by the alias translator from the grid, with the five provenance keys the
  Hub reads unchanged and the others re-meant (§6.3.1). The alias test from phase 0 is green.
- Measure the call weighting on day one; set the schedule; decide the paid tier.

**Hub:** nothing. **State:** every point in South Australia answered from memory; no upstream call
is ever triggered by the Hub; the owed backlog empties as soon as the fine tier is warm.

### Phase 2 · API v1, history, batch (one to two weeks)

- §6.3.3 in full: v1 routes, springdoc, problem details, headers, `fields=`, batch, cells layer;
  `?at=` over `cell_hour` (§6.3.2); the alias kept.
- §6.3.11: readiness with `db` and `grid`, metrics, structured logs, rate-limit headers, scopes.
- §6.3.14: the HTTP caching that the removal of `generatedAt` makes real.

**Hub:** `HttpWeatherClient` to v1 with `at` and `batch`; `WeatherManager.sweep` batching and
`at = startedAt` for the owed; `UsageService` to the new spend path; `WeatherReading` accessors (or
generated records) against the schema; `WeatherLayer` to `cells`. About a day. **State:** the Hub
asks once per sweep, gets as-at readings for its backlog, and can cache by ETag.

### Phase 3 · Fire, observations, warnings (two weeks)

- §6.3.4: `fire.published` from the GeoHub; `POST /api/v1/fire/behaviour` with McArthur FFDI and
  GFDI and the CSIRO grass FBI; the science package with worked-example tests.
- §6.3.5: BoM observations and warnings ingest, `observation` and `warnings` on the reading,
  `weather.contact` in the User-Agent, the source verification against the Bureau's current state.

**Hub:** `MetricsManager.grass` and `.published` to the endpoint and the block; `WeatherPanel` rows;
an optional `WARNINGS` enrichment. Optional; the reading is additive until then. **State:** a
reading shows the model, the instrument, the official rating and the warning on one page.

### Phase 4 · The console, and the second instance (one to two weeks)

- §6.3.8: map, providers, grid pages; the inspector; the timeline over `at=`.
- §6.3.11's remainder: ShedLock, `cell_latest` notify, backups, image size, the k6 run.
- Retire `/api/weather` on this service (the Hub's own pass-through stays for FireBuddy) and
  `coverage.geojson`.

**Hub:** `WeatherLayer.spec()` legend to the cells layer; drop `coverage`. Ten lines. **State:** a
service someone can look at, on two instances if wanted.

### Phase 5 · Forest FBI, climatology, the gridder (when there is a reason)

- §6.3.4's Vesta Mk2 once the grass model has been checked against a bad day's published FBI.
- The climatology block once `cell_day` has a year.
- The Python `weather-gridder` beside the API if and when ACCESS-C GRIB, radar or Himawari rasters
  are worth ingesting directly (§6.3.12).

**Hub:** nothing required.

---

*The three to do first, if only three:* **§6.3.1 the grid** (with §6.3.2, §6.3.6 and §6.3.15 as its
consequences), because it deletes the most, removes the Hub-triggered upstream call entirely, and
makes the budget a schedule; **§6.3.9 the contract test**, because it is a day's work and it is what
makes everything else safe to do; **§6.3.3 API v1**, because `?at=`, the batch and honest headers are
what the Hub under D-252 actually needs from this service, and because a schema-backed API is the
thing that makes the language question (§6.3.12) a free choice rather than a migration.
