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

## 2. The reach

Each station carries a polygon — its reach — which is the ground it speaks for.

**The terrain, sampled once.** For each station, 2,401 points of a digital elevation model: the
station itself, then every kilometre out to 50 km along 48 bearings, 7.5° apart. The model is the
Terrain Tiles on AWS's open data registry (Mapzen's, from SRTM, GMTED2010 and others, with
bathymetry over the sea): public, no key, no published limit, 256-pixel PNG tiles at zoom 10 —
about 125 m a pixel here — decoded with the JDK. A station's fifty-kilometre disc is some fifteen
tiles (a megabyte), neighbouring stations share them through a cache, and every tile fetched is a
row in the ledger. A background job takes one station every fifteen seconds until every station
has its terrain; the console can sample one station now from its drawer. (Open-Meteo's elevation
endpoint was tried first and counts every point as a call: 2,401 a station against 10,000 a day.) The samples are kept in `terrain` (eight-byte doubles, the station's own first) with the
position they were taken at, so a station that moves is sampled again. A new station in the file is
picked up by the job on its own.

**The rule, two numbers.** *Reach* — how far a station reaches over flat ground, 40 km by default —
and *what a hundred metres of height costs* of that reach, 10 km by default. Along each bearing a
ray walks out a kilometre at a time and stops when `distance + kmPer100m × (greatest height
difference crossed so far ÷ 100)` exceeds the reach. The *greatest* difference, not the height at
the point: a ridge is a barrier, and the far side of it is another climate even where it is the
station's own height again. A ray cut by height still reaches 3 km, so every station has some
ground. The polygon is the 48 ray ends joined. Nothing about it is stored: it is arithmetic over
the terrain in memory, so the sliders on the map preview another rule on every station at once, and
*set* makes it the rule (kept in `setting`, so a restart keeps it).

**The ocean, a third number.** A ray ends at the water: the first sample at or below sea level
(the tiles carry bathymetry, so the sea is negative and the shoreline zero) stops it half a step
short, so the beach is inside and the sea is not. A station with water inside 10 km on any bearing
is *coastal*, and every one of its rays is held to the rule's coastal limit, 25 km by default —
about how far a sea breeze carries on a summer afternoon. Land below sea level reads as water too
(Lake Eyre, at minus fifteen), which for a reach is right: a salt lake is not the station's ground.
The drawer says whether a station is coastal and how near the water is; a coastal station wears a
thin blue ring on the map.

What this does around Adelaide: West Terrace (29 m) reaches the plains north and south, stops at the
foothill scarp, never crosses to the Hills, and ends at the gulf; Mount Lofty (700 m) keeps the ridge
and not the plain; Murray Bridge (30 m) reaches east over the flat and stops short of the Hills to its
west. Two reaches may overlap — a point inside several is for the interpolation, which comes next.

## 3. The upstreams

Open-Meteo is the primary and Google Weather the overflow, each behind a budget (the published
allowance, retired at 90 % of it), a breaker (open after three failures, or at once when the refusal
names the window that ran out) and a pacer (the real per-minute limit). Every call is a row in
`upstream_call`, written before it is counted, which is what the Upstreams page and the budget read.
Open-Meteo's forecast costs three units. Nothing fetches a forecast yet.

## 4. The console

One login (`operator`, an 8-digit code, lockout after five wrong tries). The map draws every station
where it is, filled when it is reporting and hollow when it is not, coloured by what it last said;
a click opens everything held for it. The Upstreams page is the allowance table, the spend chart, the
breaker history, the Bureau's file and the recent calls. Diagnostics is the log signatures with the
startup record. API keys issues and revokes keys with a scope.

## 5. The API

`/api/v1/stations.geojson` and `/api/v1/reach.geojson` are what the map draws; `/api/v1/stations/{id}`
is what the click opens; `/api/diagnostics` is the shape The Hub's morning agent reads. Every route needs a key.

## 6. Storage

Eight tables: `api_key`, `console_user`, `api_access_log`, `log_event`, `setting` (what the console
sets and a restart must keep), `upstream_call`, `station` (`V1`), and `terrain` (`V2`). Two in-memory
registers, the stations and their terrain, rebuilt at start.
