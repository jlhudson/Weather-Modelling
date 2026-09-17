# 02 · The API

[← Docs index](README.md)

The wire contract, agreed 17 September 2026 with the Hub and Operations. **Field names are the
contract; do not rename them.** The Hub's `HttpWeatherClient` deserialises these names.

## 2.0 Common to every route

- **Auth.** Every `/api/**` route needs an API key in `X-Api-Key` or `Authorization: Bearer`. Keys are
  issued on this service's own `/console/api-keys`. A missing or invalid key is
  `401 {"error":"missing or invalid API key"}`. `OPTIONS` is open; the CORS allowlist comes from
  `weather.app.api.cors-origins` and is never `*`. The per-key quota is 600 a minute, over which the
  answer is `429 {"error":"quota exceeded"}`.
- **Health.** `/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness` are
  public. Everything else under `/actuator/**` needs the console login.
- **Conditional GETs.** Every `/api/**` GET carries an ETag and answers `304` to a matching
  `If-None-Match`.
- **Errors** are JSON `{"error": "..."}`. **Times** are ISO-8601 UTC strings. Every list body carries
  `generatedAt`.

```
export KEY=weather_...   # issued on /console/api-keys
export WX=http://localhost:8082
```

---

## 2.1 `GET /api/weather?lat=&lon=&forecast=false&force=false`

The reading at a point, from the anchor cache where one is near enough and recent enough, else an
upstream call. `forecast=true` adds the days and hours. `force=true` skips the cache — the operator
button only, because it spends allowance on every press. `400` when lat/lon are off the Earth.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather?lat=-35.02&lon=138.73"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather?lat=-35.02&lon=138.73&forecast=true"
```

```json
{
  "generatedAt": "2026-09-17T02:10:00Z",
  "query": {"lat": -35.02, "lon": 138.73},
  "provenance": {"provider": "open-meteo", "model": "...", "attribution": "...", "observedAt": "...", "fetchedAt": "...",
                 "ageMinutes": 12, "cached": true, "offsetMetres": 1200.0, "reachMetres": 15000.0,
                 "elevationDeltaMetres": null, "anchor": "<uuid>", "anchorLat": -35.0, "anchorLon": 138.7,
                 "elevationM": 210.0, "zone": "Australia/Adelaide", "decision": "cache: anchor 1.2 km away ..."},
  "current": {"at": "...", "temperatureC": 18.4, "apparentTemperatureC": 17.1, "dewPointC": 9.2, "humidityPct": 55,
              "windSpeedKmh": 22.0, "windDirectionDeg": 315, "windGustKmh": 38.0, "precipitationMm": 0.0,
              "precipitationProbabilityPct": 5, "pressureMslHpa": 1014.2, "cloudCoverPct": 40, "visibilityM": 24000,
              "uvIndex": 4.1, "daytime": true, "condition": "PARTLY_CLOUDY"},
  "fire":    {"ffdi": 11.2, "ffdiRating": "LOW-MODERATE", "peakFfdi": 24.0, "droughtFactor": 7.4, "kbdiMm": 96.0,
              "kbdiBand": "MODERATE", "meanAnnualRainfallMm": 612.0, "vapourPressureDeficitKpa": 0.9,
              "soilMoistureSurface": 0.21, "soilMoistureRootZone": 0.28, "boundaryLayerHeightM": 900.0,
              "windSpeed80mKmh": 31.0, "windDirection80mDeg": 312, "capeJkg": 40.0, "liftedIndex": 3.0,
              "estimated": false, "basis": "..."},
  "flood":   { ... },
  "drought": { ... },
  "forecast": {"days": [{"date": "...", "maxTemperatureC": 24.0, "...": "...",
                         "fire": {"...": "..."}, "flood": {"...": "..."}}],
               "hours": [{"...conditions, full...": "...",
                          "fire": {"ffdi": 9.0, "ffdiRating": "LOW-MODERATE", "droughtFactor": 7.4, "estimated": false}}],
               "windChange": null},
  "disclaimer": "Modelled weather from third-party forecast APIs, cached by proximity; ..."
}
```

`forecast` is present only when asked for. A day arrives **whole** — its own weather, its own fire
block and its own flood block on the one object — rather than as parallel arrays a reader has to join
on a date string.

**When nothing can answer**, it is a `200`, not an error:

```json
{"generatedAt": "...", "query": {...}, "provenance": null, "current": null, "fire": null,
 "flood": null, "drought": null,
 "unavailable": "no provider answered and no cached reading is near enough or recent enough",
 "disclaimer": "..."}
```

The Hub treats `provenance == null` as "no reading" and writes the incident without a weather block.
Fail open, quietly.

---

## 2.2 `GET /api/weather/status`

Everything needed to decide whether it is worth calling this service right now, and what it will cost.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather/status"
```

```json
{ "generatedAt": "...", "enabled": true,
  "providers": [ {"id":"open-meteo","host":"api.open-meteo.com","model":"...","configured":true,"unavailableReason":"",
                  "withinBudget":true,"budgetReason":"...","commercialSafe":false,"callWeight":1.0,"guardFraction":0.9,
                  "limits":{"perMinute":600,"perHour":5000,"perDay":10000,"perMonth":300000},
                  "spent":{"minute":0.0,"hour":3.0,"day":120.0,"month":2400.0},
                  "attribution":"...","lastFailure":null,"lastFailureAt":null,"coolingDownUntil":null} ],
  "cache": {"anchors": 12, "hits": 100, "misses": 10, "stale": 2, "hitRate": 90.9, "staleRate": 1.8,
            "reachKm": 15.0, "verticalWeight": 0, "ttl": "PT30M", "maxStale": "PT3H", "maxAnchors": 500},
  "tuning": {"anchorReachKm":15.0,"droughtCellRadiusKm":25.0,"riverCellRadiusKm":5.0,"ttl":"PT30M","forecastDays":3,
             "forecastHours":72,"verticalWeight":0.0,"pressure":0.1,"applied":0.0,"computedAt":"...","reason":"..."},
  "drought": {"cells": 3}, "flood": {"cells": 3} }
```

`limits` keeps its nulls rather than dropping the keys: a provider that publishes no monthly limit and
a provider whose monthly limit is unknown are the same statement here, and an absent key would say
neither. `tuning.reason` is the governor's sentence — never blank, by construction.

---

## 2.3 `GET /api/weather/spend?provider=&since=`

Allowance units the ledger recorded for one provider since an instant.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather/spend?provider=google&since=2026-09-01T00:00:00Z"
```

```json
{"generatedAt": "...", "provider": "google", "since": "2026-09-01T00:00:00Z", "spent": 12.0}
```

An unknown provider answers `spent: 0`, not `404`: the caller asked what a name has spent, and the
honest answer for a name nobody has ever called is none. A `since` that is not an ISO-8601 instant is
a `400`.

---

## 2.4 `GET /api/weather/spend/daily?provider=&from=&to=`

The same ledger cut into UTC days, inclusive at both ends.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather/spend/daily?provider=google&from=2026-09-01&to=2026-09-17"
```

```json
{"generatedAt": "...", "provider": "google", "days": [{"date": "2026-09-01", "spent": 3.0}, "..."]}
```

At most **62 days**, and past that it is a `400` rather than a silent truncation — a chart quietly
missing its left-hand half is worse than a chart that did not load. One day's spend is what was spent
since its midnight minus what was spent since the next, so a window of N days costs N + 1 queries
rather than N scans.

---

## 2.5 `GET /api/weather/coverage.geojson?hourly=false&hours=24`

The cache as a FeatureCollection. Serves only what is already held, so a display can poll it without
ever spending allowance.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/weather/coverage.geojson" | head -c 400
```

Four concentric outlines over the same ground — weather, fire danger now, drought, flood — plus every
anchor and cell as a point carrying its own detail, the radius it covers and the clock it is ageing
on. The three weather bands (fresh, stale, expired) are one colour in three line patterns and are
*differenced* rather than stacked, so they tile and the band a point falls in is the best reading
available there. Fire, drought and flood are not differenced against them: they measure different
things on different radii, and seeing all four rings at once is the point of drawing them together.

`hourly=true` attaches the trimmed hourly series to every anchor — off by default, because seventy-two
timesteps times five hundred anchors is a payload nobody asked for. `hours` caps the timesteps carried
forward of now and is itself capped at 240.

---

## 2.6 Diagnostics

The docs/26 shape, so one agent reads the Hub, Operations and this service without forking into three.

```
curl -sS -H "X-Api-Key: $KEY" "$WX/api/diagnostics?window=PT6H"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/diagnostics/logs?level=ERROR&window=P2D&limit=50"
curl -sS -H "X-Api-Key: $KEY" "$WX/api/diagnostics/logs/42"
curl -sS -X DELETE -H "X-Api-Key: $KEY" "$WX/api/diagnostics/logs?level=WARN&before=2026-09-17T00:00:00Z"
curl -sS -X DELETE -H "X-Api-Key: $KEY" "$WX/api/diagnostics/logs/42"
```

```json
{"generatedAt": "...", "window": "PT6H", "readMe": "...",
 "app": {"startedAt": "...", "readyAt": "...", "uptime": "PT4H12M"},
 "startup": {"phases": [{"phase": 1, "name": "console user", "millis": 14, "outcome": "OK"}], "failed": []},
 "logs": {"errors": 0, "warnings": 2, "held": 7,
          "retention": {"errors": "PT168H", "warnings": "PT48H"},
          "capture": {"waiting": 0, "captured": 91, "dropped": 0},
          "top": [ { ...log row... } ]},
 "weather": {"enabled": true,
             "providers": [{"id": "open-meteo", "usable": true, "reason": "...", "spent": {"...": 0.0}}],
             "cache": { ...as status... }, "tuning": { ...as status... },
             "droughtCells": 3, "riverCells": 3}}
```

A log row is
`{id, level, count, firstSeenAt, lastSeenAt, logger, thread, sourceId, pattern, message, exception, hasTrace}`;
`/logs/{id}` adds `trace`. **`sourceId` is always null here** — the Hub fills it from its source
register and this service has none. The key is carried anyway, because the row shape is shared and a
key present and null says "no source" where an absent key would say nothing.

`window` is an ISO-8601 duration, defaults to 24 hours and is capped at 30 days. There are no
`sources`, `managers`, `jobs` or `load` blocks here, and no `/api/diagnostics/sources` route — the
`weather` block stands in their place.

A `DELETE` deletes, and is logged against the consumer name on the key. That is the point: the next
morning's read holds only what has happened since.
