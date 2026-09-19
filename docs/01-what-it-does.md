# 01 · What it does

[← Docs index](README.md)

The weather, and the fire danger, at a point, now or at a time. Ask it for a latitude and a longitude
and it answers what is happening there: the conditions now, the fire picture, the flood picture, the
drought behind them, the warnings in force, and — when asked — the days and hours ahead. Every answer
says where it came from and when it was taken, and nothing in it is a guess: a value is present or it
is null.

Nothing here is pre-warmed. A reading exists because something asked for it.

---

## 1.1 Hexagons

Australia is divided into hexagons 20 km across the flats, on the Australian Albers plane so a hexagon
is 20 km from Cape York to Hobart, laid out from one anchor: hexagon `0_0` is centred on the Murray
Bridge Golf Course. The three constants are at the top of `au.gully.hexagons.Grid` — the width, the
anchor's latitude and longitude — and changing one changes every hexagon's id, which the service
notices at startup and resets the hexagon-keyed tables for. Hexagons, and only hexagons. The cells
are never stored as a list and never generated — any point's hexagon is arithmetic from the anchor — so
a hexagon only exists once something inside it has been asked about, and empty country costs nothing.

A point is answered by the reading held for its hexagon: "now" from the ground where the ground can
say (§1.2), the forecast fetched once at the hexagon's centre and shared by everything inside it. A
large fire is two to ten calls from The Hub, one after the other — the head, the flanks, the ends —
each answered from its own hexagon, new or cached. There is no "nearest reading within a radius": a
place we hold nothing for is asked about, and the ask works out what it needs.

A hexagon works out what it is made of on the ask, once each: its CFS fire ban district by
point-in-polygon, its Bureau public weather district and its station from the register when it is
created; its mean elevation over a lattice of points across it, from the mounted terrain file or from
Open-Meteo's elevation model, on the first ask; its land use as a percentage per class, from the
mounted land-cover file or from Digital Earth Australia's land cover, on the first ask (§1.8). Each is
kept on the row, so it is worked out for a hexagon once and for a hexagon nobody asks about never.

## 1.2 "Now" and the forecast, kept apart

Everything we know about a hexagon is one immutable value in one in-memory map, replaced whole when
any of it changes and rebuilt from the `hexagon` table when the service starts. Answering a request is
one map lookup and whatever the ask finds due; the database is not touched to answer.

A reading has two halves, and the service never lets one stand in for the other without saying so.

**"Now" comes from the ground.** In order, and the reading's `currentFrom` says which:

- `station` — a Bureau station sits in the hexagon and its latest values are fresh (under seventy
  minutes old). They are the reading's "now" as they are, unmoved: an observation is a fact.
- `stations` — several stations sit in the hexagon. They are blended: each weighted by the inverse
  square of its distance from the hexagon's centre, its temperature and dew point first brought to the
  hexagon's mean elevation by the lapse rates (−6.5 °C/km and −2 °C/km), humidity recomputed from the
  two, wind averaged as a vector. Two stations 300 m apart in height do not average to a temperature
  neither has.
- `neighbours` — no station in the hexagon, but two or more of its six neighbours have one reporting,
  or six or more of the eighteen hexagons in the two rings around it. The same blend, brought to this
  hexagon's elevation (`Interpolation`, W-13). The `nearby` block names the stations, their distances,
  heights and weights, the ring they were found in, and the elevation the values were brought to.
- `model` — none of the above: the forecast's hourly series read at this moment. The reading says so,
  and the map draws it in amber, not blue.

**The forecast is a prediction, fetched when an ask needs it.** A forecast is kept for its life — a
hard cap from the fetch of three hours, stretched to five once the day's allowance is 70% spent
(`Life`), read live so a budget that tightens in the afternoon stretches every forecast already held
— or until the ground says otherwise. On every ask for a hexagon with both a forecast and a station
(or stations) in it, the observation is compared with the forecast at that moment (§1.3): a forecast
that has drifted is thrown out, the ground is "now" regardless, and the days ahead are fetched again
after an hour rather than at once. Served close to the end of its life (`gully.refresh-ahead`), a
hexagon is refreshed in the background so the next ask is already fresh; several asks arriving at
once for a hexagon with nothing fetch it once. A hexagon with fresh ground values is not fetched
for at all unless a forecast is asked for. A forecast nobody has asked about for a day is dropped from
memory and the hexagon keeps only what it is made of.

**Everything happens on the ask, nothing on a timer (W-14).** The ask reads the Bureau's station file
for the state the hexagon is in when that file has not been checked for fifteen minutes, and reads the
whole file — every station in the state goes into the register, not only the one asked about — so
the next ask for any hexagon in that state is answered from memory; the state's warnings the same way
at five minutes; the CFS rating hourly and the district shapes daily for a South Australian hexagon.
Then it draws the hexagon's picture from what is held. A state nobody asks about for three hours is
not read for three hours. The only timers left are housekeeping: an hourly sweep and the nightly
backup. Every read that touched the network is written to the ledger against the hexagon that caused
it, and the map's sources panel shows it (§1.11).

**When no upstream can answer** — out of allowance, paused, failing — the last reading is served with
its time, and the reading's `source.stale` says so. When there is nothing held at all, the answer is
`available: false` with a sentence saying why, in the same shape.

## 1.3 Drift: the stations against the forecast

A hexagon with both a station and a forecast has a measure of how good the forecast is, on every ask:
the station's latest values — or the blend of the stations in the hexagon, where it holds several —
against the forecast read off its series at the same moment, on
the four things a station measures directly and a fire index turns on — **temperature, humidity, wind
speed and rain** (the station's total since 9 am against the model's for the same hours). Each is a
difference, station minus forecast, and a share of the tolerance that would be accepted: 3 °C, 20
points, 15 km/h, 5 mm. The **score is the worst of the four**, not their average — a forecast with the
humidity twenty-five points wrong is no use to a fire index however good its temperature — and at 1
the forecast has drifted and is thrown out.

Not compared: wind direction, which swings with every gust and which a vane and a model cell disagree
about on the calmest day; gusts, for the same reason; pressure, which models get right and fire does
not turn on; and anything a station does not measure. The neighbours' interpolation is never the
judge: a forecast is thrown out by a measurement, not by an estimate. Every comparison is written to `forecast_drift`,
so the accuracy of the forecasts here is a question with numbers: `/api/v1/drift` per hexagon over a
window, and the map's drift view — green agrees, red was thrown out — with the day's mean beside it.
That is where "are the forecasts good enough here" gets answered.

## 1.4 The upstreams

`Upstream` is an interface with two implementations: `OpenMeteo` (the primary: free, keyless, CC BY
4.0) and `GoogleWeather` (the overflow: three billed endpoints per fetch). Each carries its `Spec` —
host, model, licence, published allowance, what a fetch costs in the upstream's own units, the
per-minute limit, and the pause a failure earns. None of that is configuration. `gully.upstreams.order`
is the one choice: which are on, and in what order.

Per upstream, three plain mechanisms and no governor: a **budget** counted from the `upstream_call`
table against the published allowance at a 90% guard, a **breaker** that stops calling after three
consecutive failures — or at once when a refusal names the window that ran out — for the pause the
upstream asked for, and a **pacer** holding calls to the real per-minute limit. When Open-Meteo's
allowance for the day is used up or it is not answering, the fetch goes to Google on the same hexagon
with the same shape of answer, and Google's spend is counted and capped the same way.

Open-Meteo counts variables multiplied by span rather than requests: our fetch — thirty-odd hourly
variables over three days, twelve current and ten daily over seven — is charged as five units against
the ten thousand a day, which is the overhaul's figure. The console's upstreams page shows the spend
per hour and per day as bars against the allowance, with the breaker's history.

## 1.5 The Bureau

The Bureau publishes its station data as one file per state on `reg.bom.gov.au`, refreshed every ten
minutes: every automatic weather station with its latest values and its own details. A state's file
is read when an ask for a hexagon in that state finds it older than a quarter of an hour, and
downloaded only when the server says it has changed (a conditional GET, which is what the Bureau asks
for); nothing reads a file nobody has asked about. A file that is downloaded is processed whole:
every station is read out of it — never a hand-typed list — and kept in the `station` table so a
restart knows where the stations are; the latest values live in memory. On every reading the nearest
station's values ride beside the model's, with the distance and the time; where a station is inside
the hexagon, it *is* the reading's "now", and where several are, their blend is (§1.2).

A compact ledger — one row per station every six hours, holding the day's rain to 9 am and the running
maximum — is kept in `station_sample` for the drought maths (§1.7). Stations never write history.

The same server carries the warnings: one listing per state, read on request at five minutes, and the
product each item points at, read once per issue. A warning names the public weather districts it covers, a
station names the district it sits in, and that is the join: a hexagon's warnings are those covering
its nearest station's district. A fire weather warning is flagged on the fire picture.

The Bureau paused some feeds during its platform upgrade in September 2026; each file is checked live
before it is relied on, and a state whose file does not answer contributes no stations until it does.
`WEATHER_CONTACT` is sent in every request, as the Bureau asks.

## 1.6 The CFS

The official AFDRS rating per fire ban district — the rating, its Fire Behaviour Index and the total
fire ban flag, for today and four days — is read from the CFS GeoHub when an ask for a South
Australian hexagon finds it an hour old (without the district geometry: 32 KB against 6.4 MB) and put
on every reading beside the indices computed here. Published
beats derived: a reader sees both and knows which one the public were told. The fifteen district shapes
are read from the CFS's published file when an ask finds them a day old, so a hexagon's district is a
point-in-polygon test here rather than a query per hexagon.

Grass curing has no open feed (the GeoHub was checked on 18 September 2026 and carries no curing
layer), so it is entered per district on the console each week in fire season, with the date. It is
carried on every active hexagon in the district; a hexagon with no curing figure has no grassland index.

## 1.7 Drought

The fire indices need a drought factor; a drought factor needs a soil moisture deficit; a deficit
needs a year of daily rain and maximum temperature integrated into a Keetch–Byram index and then a
Griffiths factor. The drought is the hexagon's, like everything else the hexagon holds — at 20 km a
hexagon is already the scale a drought factor describes — from the Bureau stations inside it (or the
nearest within 75 km).

Starting a hexagon's drought fetches only the days the stations do not cover: running for 30 days and
needing 365, it asks Open-Meteo's archive at the hexagon's centre for the year behind the ledger and
nothing else — one fetch, about six units, once per hexagon. After that it is free: an ask that finds
the hexagon's last complete day behind the last closed rain day (9:10 am in its zone) steps it forward
from the ledger, exactly once per day, and a hexagon nobody asks about is not stepped at all. The
state is kept on the hexagon's row so a quiet hexagon picks up where it left off. A reading with no drought state has no fire index.

River discharge stays keyed on the river model's own smaller cells — 5 km, because a river is a line —
from GloFAS, once a day per cell while a hexagon in it is active. Antecedent rain comes from the same
daily series the drought uses; forecast rain from the reading's own series.

## 1.8 Terrain and land use

Elevation and land use are read for a hexagon on its first ask, once, and kept on its row (W-15).

**Elevation** is the mean over a lattice of points across the hexagon — the neighbours' values are
brought to it (§1.2), so it is the hexagon's height that has to be right, not its centre's. From the
mounted terrain file when one is there; else from Open-Meteo's elevation model, one call of one unit.
The reading's `hexagon.elevationFrom` says which (`terrain`, `open-meteo`, or `station` for a station
hexagon nobody has asked about, which carries its station's height until then).

**Land use** is a percentage per class — forest, scrub, grassland, cropland, built-up, water, bare —
counted over a lattice across the hexagon, and the class at the point asked about. From the mounted
land-cover file when one is there; else from Digital Earth Australia's land cover (Geoscience
Australia, Landsat at 30 m, one map per calendar year): one WCS call for the hexagon's box answers a
96-pixel GeoTIFF of level-4 class codes, which is counted and kept with the hexagon (`land_cover`), so
the class at any point later asked about is read off it rather than fetched. The level-4 code says what
the ground is, woody or herbaceous, and how closed the canopy is, which is what two fire indices need:
trees closed or open are forest, trees sparse or scattered are scrub, natural herbaceous cover is
grassland, cultivated herbaceous cover is cropland; a paperbark swamp is forest and a reed bed
grassland, because both burn when they dry. The latest year is looked for from last year back three,
and the year found is remembered. The reading's `hexagon.landUse.source` says which raster
(`dea-landcover-2025`, or the mounted file's name).

`GeoTiff` reads either without a GIS library — classic and BigTIFF, stripped or tiled, uncompressed,
Deflate, LZW or PackBits, geographic or Australian Albers, from a file or from memory. Mounted files
are optional and never committed; nothing need be mounted for a hexagon to know its height and its
ground.

Land use decides which index leads: mostly trees and scrub, the forest index; mostly grass and crop, the
grassland indices; mostly water or built-up, both are carried and `appliesToPct` says how little.

## 1.9 The fire picture

An *active* hexagon — one someone has asked about — carries the whole set, computed from what it holds
and replaced whenever any input changes: the McArthur forest index (FFDI) and grassland index (GFDI),
the AFDRS grassland model's Fire Behaviour Index and rating (the CSIRO grassland fire spread meter with
the Cruz curing function, on the published constants, with the intensity-to-FBI table), the official
district rating and total fire ban, the wind now and its next change within 48 hours, the drought
index, the warnings, the land use and which index leads, and the forecast indices day by day with the
deficit carried forward through the forecast's rain. The map colours by any of it.

The forest fire behaviour model of the AFDRS is not here yet; the doc that catalogued this rebuild
puts it after the grassland model has been checked against a bad day's published number.

## 1.10 History

A snapshot of a hexagon's current conditions and fire picture — never the forecast — is written to
`reading_snapshot` when an ask about the hexagon carries a `ref` — what the reading is for: an
incident id, a job number, a planning exercise, anything the caller names — at most once every three
hours per hexagon. This is a weather service, not an incident service: a reading is asked for for any
reason, and the ref is the caller's word for it. Only hexagons asked about with a ref have history;
nothing is ever deleted from the table by the service; a nightly export goes to the backups volume.
Asking the API for a past time returns the snapshot nearest that time, with its own time, or says
there is none.

## 1.11 The console map

A full-bleed map with a few panels floating over it, and one question at a time: what to colour by.

**The rail** (top left) asks it in two steps. A side — **Now** from the ground, **Forecast** from the
model, or **Δ**, one against the other — and a variable: for now and the forecast, where the values
come from (or the forecast's remaining life), temperature, humidity, wind, gust, rain and age; for Δ,
temperature, humidity and wind as now minus forecast, and the drift score with its 24-hour mean.
Under it, folded, the fire layers (FFDI, GFDI, FBI in their rating colours, the CFS rating, the
drought factor, KBDI, curing), the ground (elevation, land use by its largest share, which index
leads, the burnable share) and the requests (minutes since a hexagon was last asked about, which is
what drives every read). Then what to draw on top: the stations as a point cloud, wind arrows, value
labels, the tessellation, hexagons as points (automatic when zoomed out to the continent), and only
the hexagons holding a forecast. Keys `1`, `2`, `3` pick the side.

**The figures** (top right): hexagons held, how many take "now" from the ground (with the split by
station, blend and neighbours on hover), forecasts held and their life, hexagons where the model is
standing in, forecasts thrown out, and the share of today's allowance used — and a line to the sources
drawer, which shows that nothing is read but on request: each source with its cadence and a bar
filling towards its next check, when an ask last checked and read it, what it holds (a station file's
count is the whole file), which hexagon's ask caused that; and the ledger's last reads, each against
the hexagon it was read for.

**The legend** (bottom centre) is the scale the colours mean, with the distribution of the hexagons
drawn on it: a histogram over the ramp and the mean for a number, swatches with counts for a category.
The stations are coloured on the same scale where they measure the variable shown, filled where the
observation is fresh and hollow where the file for their state has not been asked for lately — so a
station that disagrees with its hexagon is visible as a dot of a different colour.

**The timeline** (bottom) runs a week back and three days ahead, with a tick at each local midnight.
Behind now the layer is what the snapshots say was "now" then; ahead of now it is the forecast series
read at that hour, with that hour's fire indices and that day's CFS rating, for every hexagon holding
a forecast — and the rail follows, because the future has no "now": the Now and Δ sides grey out and
the forecast side takes over. Play steps an hour at a time; the arrow keys nudge, space plays,
`0` returns to now. Dragging asks the service for the layer at that hour (not cached; a few
milliseconds each).

On every hexagon that knows its weather, an arrow the way the wind blows, its length by the speed
(from zoom 7), and the temperature, humidity and speed as a label (from zoom 9): black for the
ground's values, amber for the model's. A tooltip carries both halves side by side with the
difference, the drift, the fire indices, the height and the land use. A click opens the drawer:
now against the forecast in one table with the deltas, the drift as a meter against its tolerance,
the stations that made "now", the land use as a bar, and everything else held. The map never
fetches; the probe on a point is an ask, and says so. The page is locked to the device: on a phone
the rail is a sheet at the bottom and the figures fold into the legend.

## 1.12 What is deliberately not here

The decision about *when* to ask stays with the caller (The Hub's D-249: the stagger across what it
watches, the re-ask on a change, the per-tick ceiling). This service knows nothing about what a reading
is for beyond the ref the caller attaches; it holds the cache, the caller holds the question. There is
no Hub-side cache and no Hub-side fallback (D-252): what this service does not answer is asked about again.
