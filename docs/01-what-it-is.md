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

**The ocean, a third number.** A ray ends at the water: the first sample of it (the tiles carry
bathymetry, so the sea is negative and the shoreline zero) stops it half a step short, so the beach
is inside and the sea is not. Water is water only when it is at least 3 km across along the ray —
three samples in a row at or below sea level (W-4): a river is a line and never is, so the lower
Murray, which the tiles read at sea level, is crossed like any dip in the ground; the sea and Lake
Alexandrina are areas and always are. A station with water inside 10 km on any bearing
is *coastal*, and every one of its rays is held to the rule's coastal limit, 25 km by default —
about how far a sea breeze carries on a summer afternoon. Land below sea level reads as water too
(Lake Eyre, at minus fifteen), which for a reach is right: a salt lake is not the station's ground.
The drawer says whether a station is coastal and how near the water is; a coastal station wears a
thin blue ring on the map.

What this does around Adelaide: West Terrace (29 m) reaches the plains north and south, stops at the
foothill scarp, never crosses to the Hills, and ends at the gulf; Mount Lofty (700 m) keeps the ridge
and not the plain; Murray Bridge (30 m) reaches east over the flat and stops short of the Hills to its
west. Two reaches may overlap — a point inside several is for the interpolation, which comes next.

## 3. The record and the drought

**The record.** Every observation a real station makes goes into an open six-hour window; when a
reading arrives past the window's end - 3 am, 9 am, 3 pm, 9 pm local - the window is written to
`station_hour6` (readings, the temperature's extremes and mean, the humidity's extremes, the wind's
mean and maximum, the strongest gust, and the Bureau's running figures as they stood). The first
reading at or after 9 am closes the Bureau's day that ended there, into `station_day`: the day is
dated by the 9 am it began at, its rain is the total to 9 am the reading publishes, its maximum the
highest of the day's windows and the running maximum published just before 9 am. A day the service
was not running for is left absent and the archive fills it. Both tables are kept 548 days.

**The backfill.** A background job takes one station every fifteen seconds and asks Open-Meteo for
the days its year lacks: the reanalysis archive (hourly rain and temperature, folded into 9 am days;
about 26 units for a year, one per fortnight) for the days older than the archive's six-day lag, the
forecast endpoint's past days (one unit) for the rest. Only the missing days are asked for, a gap
of three or fewer is not worth a call, and a station is left six hours between attempts. The
archive's day never replaces a day the station made itself.

**The drought.** Each station's KBDI is integrated from its own record: from field capacity at the
start of the year behind today, day by day, with the mean annual rainfall the run needs taken from
that same year; the drought factor from the deficit and the last twenty days of rain, today's so
far last. Twenty days is the least a record can speak from; a year less a fortnight is complete.
Nothing is stored - it is arithmetic over the record, memoised until the record or the day changes -
and it travels on the stations feed (`kbdiMm`, `droughtFactor`) and the station's detail, with the
last thirty days and eight windows of the record behind it.

## 4. The reading, and the points of our own

**A click, or `/api/v1/reading?lat=&lon=`, asks for the weather at a point** (W-8). The Bureau's
stations whose reach contains it are the members; each weighs `1 / cost²`, the cost being the distance
plus what the greatest height difference along the ray towards the point costs - the reach's own
arithmetic, so a station across rising ground counts for less. Temperature, apparent temperature,
dew point and the day's maximum are brought to the point's height by the lapse rate (6.5 °C per
kilometre; 2 for the dew point) before they are blended; humidity, wind, gust, pressure and rain
are blended as they are, the wind's direction as a vector. Each station's KBDI and drought factor
are blended by the same weights - one polygon per station carries its current and its drought
alike. A station lacking a value stays out of that value's blend, and the reading names the
stations behind every value. The forest fire danger index (McArthur Mk 5, Noble, Bary and Gill 1980)
is computed from the blended temperature, humidity, wind and drought factor, and is null when any
is missing rather than made from a guess.

**Where no station can say what the weather is** - none reaches, or those that do carry no
temperature - **a point of our own answers** (W-7). One already dropped whose reach contains the
place is used: its current fetched again if older than an hour, its record filled for the missing
days, the ask remembered. Otherwise a new one is dropped there, as a station with the kind `point`:
its terrain sampled and its reach drawn by the same rule, its current from Open-Meteo (the current
block, with the rain since 9 am and the day's total summed from the 48 hours of series behind it),
and a year of the archive for its record. The first ask at a new place takes a few seconds; every
later ask inside its reach is immediate. A point no ask has used for 548 days is dropped again,
record and all. A point never gets a six-hour ledger: its days come from the archive, not from
folding its fetches. On the map a point is an amber diamond, filled with its value like a station.

## 5. The probe

Click anywhere on the map, or ask `/api/v1/stations/at?lat=&lon=` with a key, and the answer is the
stations that speak for that point (W-5): every station whose reach contains it, nearest first, each
with its distance and bearing, how far above or below the point it sits (the point's own height is
one read of the elevation tiles), what it last said and how old that is, and how far past the point
its ray towards it goes; then the nearest three whose reach does not contain it, with why their ray
stopped short. Nothing is blended: these are the ingredients a reading at the point will be made
from, and the interpolation between them is the next decision.

## 6. The upstreams

Open-Meteo is the primary and Google Weather the overflow, each behind a budget (the published
allowance, retired at 90 % of it), a breaker (open after three failures, or at once when the refusal
names the window that ran out) and a pacer (the real per-minute limit). Every call is a row in
`upstream_call`, written before it is counted, which is what the Upstreams page and the budget read.
Open-Meteo's forecast costs three units. Nothing fetches a forecast yet.

## 7. The console

One login (`operator`, an 8-digit code, lockout after five wrong tries). The map draws every station
where it is, filled when it is reporting and hollow when it is not, coloured by what it last said;
a click opens everything held for it. The Upstreams page is the allowance table, the spend chart, the
breaker history, the Bureau's file and the recent calls. Diagnostics is the log signatures with the
startup record. API keys issues and revokes keys with a scope.

## 8. The API

`/api/v1/stations.geojson` and `/api/v1/reach.geojson` are what the map draws; `/api/v1/stations/{id}`
is what a click on a station opens and `/api/v1/stations/at?lat=&lon=` what a click anywhere else opens; `/api/diagnostics` is the shape The Hub's morning agent reads. Every route needs a key.

## 9. Storage

Ten tables: `api_key`, `console_user`, `api_access_log`, `log_event`, `setting` (what the console
sets and a restart must keep), `upstream_call`, `station` (`V1`), `terrain` (`V2`), `station_hour6`
and `station_day` (`V3`). Three in-memory registers - the stations, their terrain, their days - rebuilt
at start.
