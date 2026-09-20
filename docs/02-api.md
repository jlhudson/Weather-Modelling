# 02 · The API

[← Docs index](README.md)

Version 1, 19 September 2026. **The reading's shape is the contract**, written down once as
[contract/reading.schema.json](../src/main/resources/contract/reading.schema.json), kept identically
in The Hub (`hub-services/src/main/resources/contract`), tested here against what is served and there
against what is read, and served at `/api/v1/contract/reading.schema.json` so a consumer can compare
its copy at start. The OpenAPI document generated from the typed responses is at `/api/v1/openapi.json`.

## 2.0 Common to every route

- **Auth.** Every `/api/**` route needs an API key in `X-Api-Key` or `Authorization: Bearer`, issued on
  `/console/api-keys` with a scope: `ALL`, `READINGS` (readings, fire-indices, status, spend),
  `LAYER` (the hexagons), `DIAGNOSTICS`. A missing or invalid key is `401`, a key outside its scope
  `403`. `OPTIONS` is open; the CORS allowlist comes from `WEATHER_CORS_ORIGINS` and is never `*`.
- **Rate limits.** Per key, 600 a minute and 100,000 a day by default, answered in the standard
  `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` and `RateLimit-Policy` headers; over
  either, `429` with `Retry-After`.
- **Errors** are RFC 9457 problem details, `application/problem+json`:
  `{"type":"about:blank","title":"Bad Request","status":400,"detail":"lat must be -90..90 ..."}`.
  One format, including the filter's `401`, `403` and `429`.
- **Caching.** Every successful GET carries a weak `ETag` (`W/"…"`) hashed from the body, and a
  matching `If-None-Match` is a `304`. No body carries a generated-at time, so the fingerprint only
  changes when the reading does. A reading also carries `Cache-Control: private, max-age=<seconds to
  its expiry>` and `Last-Modified` (when its "now" values were taken); the hexagon layer carries its
  own `ETag` that changes only when a hexagon has. Bodies are gzip-compressed on request — the layer
  is about 95 KB on the wire against 1.3 MB decoded. The tag is weak on purpose: Tomcat will not
  compress a response carrying a strong one (a gzipped body is a different representation), and a
  strong tag here had silently switched compression off for the whole API.
- **Times** are ISO-8601 UTC. Dates are local calendar days at the point.

```
export KEY=weather_...   # issued on /console/api-keys
export WX=http://localhost:8082
```

---

## 2.1 `GET /api/v1/readings?lat=&lon=&forecast=false&at=&ref=`

The reading at a point, from the hexagon it falls in: fetched if the hexagon holds nothing or its
reading has expired, served from memory otherwise, refreshed in the background when close to expiry.
One point per request, always.

- `forecast=true` adds the days and hours ahead.
- `ref=<anything>` says what the reading is for — an incident id, a job number, a planning exercise —
  which activates the hexagon's history: a snapshot of the conditions and the fire picture is written,
  tagged with the ref, at most once every three hours per hexagon.
- `at=<instant>` answers from history: the snapshot nearest that time for the point's hexagon, with
  its own time in `history.at`, or `available: false` when the hexagon has none.
- `400` when lat/lon are off the Earth or outside Australia; `at` in the future is a `400` too.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/readings?lat=-35.02&lon=138.73"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/readings?lat=-35.02&lon=138.73&forecast=true&ref=INC0103"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/readings?lat=-35.02&lon=138.73&at=2026-09-18T02:00:00Z"
```

```json
{
  "schema": "gully/reading/1",
  "available": true,
  "unavailable": null,
  "point": {"lat": -35.02, "lon": 138.73},
  "hexagon": {"id": "51_-263", "lat": -35.0141, "lon": 138.7492, "widthKm": 15.0, "sides": 6,
              "elevationM": 482.0, "elevationFrom": "model", "slopeDeg": null, "zone": "Australia/Adelaide",
              "fireBanDistrict": "MOUNT LOFTY RANGES", "bureauDistrict": "SA_PW001",
              "landUse": null, "stationId": null, "kind": "forecast",
              "activatedAt": "...", "refreshedAt": "...", "expiresAt": "..."},
  "source": {"upstream": "open-meteo", "model": "best_match", "attribution": "Weather data by Open-Meteo.com, CC BY 4.0",
             "fetchedAt": "...", "currentExpiresAt": "...", "forecastExpiresAt": "...", "stale": false},
  "at": "2026-09-18T13:45:00Z",
  "current": {"at": "...", "temperatureC": 10.7, "apparentTemperatureC": 8.1, "dewPointC": 7.5, "humidityPct": 81,
              "windSpeedKmh": 9.4, "windDirectionDeg": 200, "windGustKmh": 18.0, "precipitationMm": 0.0, "...": "..."},
  "currentFrom": "model",
  "station": {"id": "023000", "name": "ADELAIDE (WEST TERRACE / NGAYIRDAPIRA)", "lat": -34.9257, "lon": 138.5832,
              "heightM": 29.32, "distanceKm": 18.4, "insideHexagon": false, "at": "2026-09-18T13:50:00Z",
              "temperatureC": 15.0, "humidityPct": 45, "windSpeedKmh": 11.0, "windDirectionDeg": 42, "windDirection": "NE",
              "windGustKmh": 17.0, "pressureMslHpa": 1025.8, "rainSince9amMm": 0.0, "rain24hMm": 0.0,
              "maxTemperatureC": 23.4, "minTemperatureC": null, "visibilityKm": 71.0, "cloud": "Clear", "...": "..."},
  "fire": {"ffdi": 3.1, "ffdiRating": "LOW-MODERATE", "peakFfdi": 14.2, "droughtFactor": 6.8, "kbdiMm": 88.0, "kbdiBand": "DRYING",
           "leads": null, "appliesToPct": null,
           "grass": {"curingPct": 80, "curingEnteredOn": "2026-09-14", "fuelLoadTHa": 4.5, "condition": "grazed",
                     "gfdi": 1.9, "gfdiRating": "LOW-MODERATE", "spreadKmh": 0.25, "moisturePct": 18.8,
                     "rateOfSpreadKmh": 0.31, "intensityKwm": 720, "flameHeightM": 0.6, "fbi": 7, "afdrsRating": "No Rating"},
           "official": {"district": "Mount Lofty Ranges", "rating": "No Rating", "fbi": 0, "totalFireBan": false,
                        "date": "2026-05-01", "from": "...", "to": "...", "days": [ "..." ], "readAt": "..."},
           "wind": {"speedKmh": 9.4, "directionDeg": 200, "gustKmh": 18.0, "band": "LIGHT",
                    "change": {"at": "...", "fromDeg": 315, "toDeg": 225, "speedKmh": 32.0, "gustKmh": 55.0}},
           "fireWeatherWarning": false,
           "vapourPressureDeficitKpa": 0.25, "soilMoistureSurface": 0.21, "soilMoistureRootZone": 0.28,
           "boundaryLayerHeightM": 900.0, "windSpeed80mKmh": 31.0, "windDirection80mDeg": 312, "capeJkg": 40.0, "liftedIndex": 3.0},
  "flood": {"rain1dMm": 0.0, "rain2dMm": 0.0, "rain3dMm": 2.4, "rain7dMm": 11.2, "forecastRain6hMm": 0.0, "...": "...",
            "riverDischargeCumecs": 12.0, "riverDischargeMeanCumecs": 20.0, "dischargeRatioToMean": 0.6, "riverTrend": "STEADY",
            "outlook": [{"date": "2026-09-19", "rainMm": 2.4, "rainProbabilityPct": 60, "riverDischargeCumecs": 12.0, "dischargeRatioToMean": 0.6}]},
  "drought": {"kbdiMm": 88.0, "kbdiBand": "DRYING", "droughtFactor": 6.8, "meanAnnualRainfallMm": 612.0,
              "spunUpFrom": "2025-09-18", "computedFor": "2026-09-17", "days": 365, "recentRainMm": [ "20 numbers" ]},
  "warnings": [{"id": "IDS21037", "title": "Severe Weather Warning", "phenomena": "for DAMAGING WINDS", "headline": "...",
                "hazard": "SWW", "severity": "STD", "issuedAt": "...", "from": "...", "until": "...", "link": "..."}],
  "forecast": {"days": [{"date": "2026-09-19", "maxTemperatureC": 19.0, "minTemperatureC": 9.2, "...": "...",
                         "fire": {"ffdi": 8.0, "ffdiRating": "LOW-MODERATE", "gfdi": 7.5, "gfdiRating": "LOW-MODERATE",
                                  "fbi": 6, "afdrsRating": "No Rating", "kbdiMm": 87.0, "droughtFactor": 6.6, "...": "..."},
                         "flood": {"rainMm": 2.4, "rainProbabilityPct": 60, "riverDischargeCumecs": 12.0, "dischargeRatioToMean": 0.6}}],
               "hours": [{"at": "...", "temperatureC": 10.2, "humidityPct": 83, "windSpeedKmh": 10.1, "windDirectionDeg": 205,
                          "windGustKmh": 18.0, "precipitationMm": 0.1, "precipitationProbabilityPct": 20, "condition": "Overcast",
                          "fire": {"ffdi": 3.1, "ffdiRating": "LOW-MODERATE", "gfdi": 2.0, "gfdiRating": "LOW-MODERATE", "fbi": 1, "afdrsRating": "No Rating", "droughtFactor": 6.8}}],
               "windChange": {"at": "...", "fromDeg": 315, "toDeg": 225, "speedKmh": 32.0, "gustKmh": 55.0}},
  "history": null,
  "disclaimer": "..."
}
```

What each block is:

- **`hexagon`** — what the point's hexagon is made of, and the state of what it holds. `kind` is
  `station`, `forecast`, `both` or `bare`; `expiresAt` is when the forecast's life ends. `elevationM`
  is the hexagon's mean height and `elevationFrom` where it came from (`terrain`, `open-meteo`, or
  `station` until the first ask). `landUse` is the share per class, `leads` which fire index that
  makes lead, `burnablePct` how much of the hexagon the indices describe, `point` the class at the
  point itself, and `source` the raster (`dea-landcover-2025`, or a mounted file's name); null until
  the first ask has read it (docs/01 §1.8).
- **`source`** — the upstream the forecast came from, when it was fetched, when its life ends
  (`expiresAt`) under the cap in force now (`life`, `PT3H`, or `PT5H` while the allowance is tight), and
  `stale`, true when its life has ended and nothing has answered since. Null on a station-only reading.
- **`drift`** — the stations in the hexagon against the forecast at the observation's time (docs/01 §1.3):
  `temperatureC`, `humidityPct`, `windKmh`, `rainMm` as observed minus forecast, `score` (the worst as a
  share of its tolerance), `worst`, and `drifted` — true when that threw the forecast out. `stationId`
  is the station, or the stations joined with `+` where the hexagon's blend was the judge. Null where
  the hexagon has no station, no forecast, or no comparison yet.
- **`at`, `current`, `currentFrom`** — the conditions now and where they came from (docs/01 §1.2):
  `station` when the Bureau station in the hexagon supplied them as they are (its own fields; the
  model-only ones are null); `stations` when several inside it were blended at the hexagon's
  elevation; `neighbours` when the stations around it were, brought to its elevation; `model`
  otherwise. `at` is the time they describe.
- **`nearby`** — with `currentFrom` of `stations` or `neighbours`: `ring` (0 inside the hexagon, else 1
  or 2), each station used with its `distanceKm`, `heightM` and `weight` (the shares sum to 1),
  `elevationM` the values were brought to, `elevationApplied` (false when the hexagon or a station
  had no height, in which case the values are weighted as they are), and the two lapse rates. Null
  otherwise.
- **`station`** — the nearest station's latest values, inside the hexagon or not, with the distance;
  `windShift`, the wind change it has just measured (docs/01 §1.5) — `grade` the higher of
  `swingGrade` and `speedGrade`, each `slight`, `marked`, `sharp` or null, the directions and speeds
  it went between, `overMinutes`, and a `description` in words — or null; and `recent`, its last
  readings newest first, up to six.
- **`fire`** — the fire picture (docs/01 §1.8). `grass` is null where the district has no curing figure;
  `official` is null outside South Australia; `leads` is null without land use.
- **`flood`**, **`drought`** — as before; `drought` is null until the area's state exists, and then so is
  every index.
- **`warnings`** — the Bureau warnings in force for the hexagon's district.
- **`forecast`** — only with `forecast=true`. A day arrives whole with its own `fire` and `flood`; an
  hour carries the eight fields anyone reads and its own indices.
- **`history`** — only with `at=`: the snapshot's own time, when it was taken and the ref it was taken for.

**When nothing can answer**, it is a `200`, not an error, in the same shape:

```json
{"schema": "gully/reading/1", "available": false,
 "unavailable": "no upstream answered and the hexagon holds no reading: every upstream is out of allowance, paused or failing",
 "point": {"lat": -35.02, "lon": 138.73}, "hexagon": { "..." }, "source": null, "at": null, "current": null, "currentFrom": null,
 "station": { "..." }, "fire": null, "flood": null, "drought": null, "warnings": [], "forecast": null, "history": null, "disclaimer": "..."}
```

The Hub treats `available == false` as "no reading" and asks again on its next sweep.

---

## 2.2 `GET /api/v1/hexagons.geojson?at=`

Every hexagon held, as a `FeatureCollection` of polygons, each carrying the values a map colours by,
with "now" and the forecast kept apart. With `at=` behind now, the values as they were, from the
snapshots (a snapshot stands for six hours; only hexagons asked about with a ref have one). With `at=`
ahead of now — up to 72 hours, the hourly series — the values as they are forecast to be for every
hexagon holding a forecast: `fc*` read off the series at that hour, `ffdi`, `gfdi` and `fbi` as that
hour's indices with that day's projected drought factor, `officialRating` as the CFS rating for that
day, `ahead: true` and `aheadHours`; there is no "now" in the future, so `from` is null. `meta.mode` is
`now`, `history` or `ahead`. The properties, by group: `from` (`station`, `stations`, `neighbours`, `model` or null)
and `nowTemperatureC`, `nowHumidityPct`, `nowWindKmh`, `nowWindDeg`, `nowGustKmh`, `nowRainMm`,
`nowAt`, `nowAgeMinutes`, `nowStations`, `nowRing`, and — at the reach in force (W-18), now only —
`stationsInReach`, how many stations count for the hexagon, and `stationsReporting`, how many of them
are fresh; `fcTemperatureC`, `fcHumidityPct`, `fcWindKmh`,
`fcWindDeg`, `fcGustKmh`, `fcRainMm`, `fcFetchedAt`, `fcExpiresAt`, `fcMinutesLeft`, `stale`,
`upstream`; `diffTemperatureC`, `diffHumidityPct`, `diffWindKmh` (now minus forecast) and the drift
(`drift`, `drifted`, `driftWorst`, `drift24h`, ...); the fire picture (`ffdi`, `ffdiRating`, `gfdi`,
`fbi`, `afdrsRating`, `officialRating`, `totalFireBan`, `droughtFactor`, `kbdiMm`, `curingPct`,
`warnings`, `windChangeAt`); the ground (`elevationM`, `elevationFrom`, `landUse`, `landDominant`,
`leads`, `burnablePct`, `landSource`); and the asks (`kind`, `active`, `warm`, `lastAskedAt`,
`askedMinutesAgo`, `asks`). `meta` carries the counts, including `nowFrom` — how many hexagons take
"now" from each source — `reachKm`, the station reach in force, and `backHours`/`aheadHours`, how far `at=` reaches either way. Pre-rendered once per change and fingerprinted, so a
map polling every minute gets `304` until something changes. With `at=`, the values as they were —
only hexagons asked about with a ref have a value then. Never fetches. Served as `application/geo+json`;
a client that accepts only `application/json` is answered as that rather than refused, and the same
goes for the contract's `application/schema+json`.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/hexagons.geojson" -D - -o /dev/null | grep -i etag
```

## 2.3 `GET /api/v1/drift?hours=24` and `GET /api/v1/drift/recent?hexagon=&limit=`

How the forecasts are doing against the stations (docs/01 §1.3). The first is per hexagon over a
window: how many comparisons, the mean and worst score, how many forecasts were thrown out, the mean
absolute difference of each of the four, and the latest comparison; with the tolerances and the life
in force. The second is the comparisons themselves, newest first, for one hexagon or all.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/drift?hours=168"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/drift/recent?hexagon=0_0&limit=50"
```

`GET /api/v1/hexagons` is the same as a list of rows; `GET /api/v1/hexagons/{id}` is everything held
for one — its reading, its drought state, its river, its history.

## 2.4 `GET /api/v1/fire-indices?...`

The indices for given inputs, from the one set of formulas: McArthur's forest and grassland meters and
the AFDRS grassland model with its rating. For a calculator, a what-if, or a check against a published
worked example. `curingPct` is needed for any grassland figure; `fuelLoadTHa` defaults to 4.5;
`condition` is `natural`, `grazed` or `eaten-out`.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/fire-indices?temperatureC=30&humidityPct=20&windKmh=30&droughtFactor=10&curingPct=100&condition=natural"
```

```json
{"inputs": {"temperatureC": 30.0, "humidityPct": 20, "windKmh": 30.0, "droughtFactor": 10.0},
 "forest": {"ffdi": 27.8, "ffdiRating": "VERY HIGH"},
 "grass": {"curingPct": 100.0, "fuelLoadTHa": 4.5, "condition": "natural", "mcArthurMoisturePct": 7.3, "gfdi": 26.3, "gfdiRating": "VERY HIGH",
           "spreadKmh": 3.42, "moisturePct": 6.2, "rateOfSpreadKmh": 7.21, "intensityKwm": 16770, "flameHeightM": 3.3, "fbi": 47, "afdrsRating": "High"}}
```

## 2.5 `GET /api/v1/status` and the spend reads

Everything needed to decide whether it is worth calling this service right now and what it will cost:
each upstream with its allowance, its spend per window, its breaker and the reason it may not be
called; the sources with when each last answered; and what is held.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/status"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/upstreams/open-meteo/spend?since=2026-09-01T00:00:00Z"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/upstreams/open-meteo/spend/daily?from=2026-09-01&to=2026-09-18"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/v1/upstreams/open-meteo/spend/hourly"
```

`spend/daily` is at most 62 days and a `400` past that.

## 2.6 The contract and the document

```
curl -sS "$WX/api/v1/contract/reading.schema.json"     # public
curl -sS "$WX/api/v1/openapi.json"                     # public
```

## 2.7 Diagnostics

Unchanged in shape from the split: `GET /api/diagnostics?window=`, `/logs?level=&window=&limit=`,
`/logs/{id}`, `DELETE /logs?level=&before=`, `DELETE /logs/{id}`. The service block is now `gully`
(the upstreams, the sources, what is held) in place of the old `weather` block. Needs the
`DIAGNOSTICS` or `ALL` scope.

## 2.8 `GET /api/weather`

The old route in its old shape — `provenance`, `current`, `fire`, `flood`, `drought`, `forecast`,
`generatedAt`, `unavailable` — built from the new reading, for one release. `estimated` is always
false. Gone with the next release.

## 2.9 The health probes

`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are public; readiness
includes the database, so a dead database shows as a restarting container.
