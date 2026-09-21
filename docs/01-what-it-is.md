# What it is

Gully holds South Australia's Bureau stations and, for each, the ground it speaks for. This page is
the mechanism as it stands; the decisions behind it are in [02-decisions.md](02-decisions.md).

## 1. The stations

The Bureau publishes one observation file per state, refreshed every ten minutes
(`https://reg.bom.gov.au/fwo/IDS60920.xml` for South Australia). Gully reads it every ten minutes
with a conditional GET, so a file the Bureau has not changed costs a round trip and no download, and
takes the whole file in: every station's id, WMO number, name, position, height, zone and district
go into the `station` table (an upsert, so a station that moves or is renamed follows the file), and
its latest values — temperature, apparent temperature, dew point, humidity, wind and gust, pressure,
rain since 9 am and to 9 am, the day's maximum and minimum, the sky — into memory. The last six
readings per station are kept in memory too, for the console. Nothing about an observation is
written to the database: it is ten minutes old at most and the next file replaces it.

A station is *reporting* when its latest observation is under seventy minutes old.

## 2. The reach *(W-2, to come)*

Each station will carry a polygon — its reach — drawn once from the terrain around it and recomputed
from those samples whenever the rule is turned. See W-2 in the decisions.

## 3. The upstreams

Open-Meteo is the primary and Google Weather the overflow, each behind a budget (the published
allowance, retired at 90 % of it), a breaker (open after three failures, or at once when the refusal
names the window that ran out) and a pacer (the real per-minute limit). Every call is a row in
`upstream_call`, written before it is counted, which is what the Upstreams page and the budget read.
Open-Meteo's forecast costs three units; its elevation endpoint — a hundred points of a 90 m digital
elevation model in one call — costs one. Nothing fetches a forecast yet; the elevation endpoint is
what the reach is drawn from.

## 4. The console

One login (`operator`, an 8-digit code, lockout after five wrong tries). The map draws every station
where it is, filled when it is reporting and hollow when it is not, coloured by what it last said;
a click opens everything held for it. The Upstreams page is the allowance table, the spend chart, the
breaker history, the Bureau's file and the recent calls. Diagnostics is the log signatures with the
startup record. API keys issues and revokes keys with a scope.

## 5. The API

`/api/v1/stations.geojson` is what the map draws; `/api/v1/stations/{id}` is what the click opens;
`/api/diagnostics` is the shape The Hub's morning agent reads. Every route needs a key.

## 6. Storage

Seven tables, all in `V1__gully.sql`: `api_key`, `console_user`, `api_access_log`, `log_event`,
`setting` (what the console sets and a restart must keep), `upstream_call`, `station`. One in-memory
register, rebuilt from `station` at start.
