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

Australia is divided into hexagons 32 km across the flats, on the Australian Albers plane so a hexagon
is 32 km from Cape York to Hobart, laid out from one anchor: hexagon `0_0` is centred on the Murray
Bridge Golf Course. The three constants are at the top of `au.gully.hexagons.Grid` — the width, the
anchor's latitude and longitude — and changing one changes every hexagon's id, which the service
notices at startup and resets the hexagon-keyed tables for. Hexagons, and only hexagons. The cells
are never stored as a list and never generated — any point's hexagon is arithmetic from the anchor — so
a hexagon only exists once something inside it has been asked about, and empty country costs nothing.

A point is answered by the reading held for its hexagon, fetched once at the hexagon's centre and
shared by everything inside it. A large fire is two to ten calls from The Hub, one after the other —
the head, the flanks, the ends — each answered from its own hexagon, new or cached. There is no
"nearest reading within a radius" and no guessing from neighbours: a place we hold nothing for is
fetched.

When a hexagon is first created it works out what it is made of, once: its elevation and mean slope
from the mounted terrain file, its land use from the mounted land-cover file (as a percentage per
class and the class at the point), its CFS fire ban district by point-in-polygon, its Bureau public
weather district and its nearest station from the station register. None of that is worked out on
request.

## 1.2 What a hexagon holds, and how fresh it is

Everything we know about a hexagon is one immutable value in one in-memory map, replaced whole when
any of it changes and rebuilt from the `hexagon` table when the service starts. Answering a request is
one map lookup; the database is not touched.

**The reading is kept until the upstream says it is stale.** Open-Meteo's current block states the
quarter-hour it covers, so a reading is current until that quarter-hour ends; its models update hourly,
so the hourly and daily series are re-asked for an hour after they were fetched. Served close to expiry
(`gully.refresh-ahead`, three minutes), a hexagon is refreshed in the background so the next ask is
already fresh. Several asks arriving at once for a hexagon with nothing fetch it once. The Hub sweeps
keeps asking, so the hexagons it asks about stay warm and go cold on their own when it stops; a
forecast nobody has asked about for a day is dropped from memory
and the hexagon keeps only what it is made of.

**A hexagon with a Bureau station in it is always alive.** Its "now" is the station's values, which
arrive every ten minutes for nothing, so the upstream is called for its forecast only, and only when a
forecast is asked for; it keeps answering "now" when Open-Meteo is offline. Every station gets a
hexagon of its own on the first poll, so the map can show it — but such a hexagon is not *active*
until someone asks about it.

**When no upstream can answer** — out of allowance, paused, failing — the last reading is served with
its time, and the reading's `source.stale` says so. When there is nothing held at all, the answer is
`available: false` with a sentence saying why, in the same shape.

## 1.3 The upstreams

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

## 1.4 The Bureau

The Bureau publishes its station data as one file per state on `reg.bom.gov.au`, refreshed every ten
minutes: every automatic weather station with its latest values and its own details. All seven are
checked every ten minutes and downloaded only when the server says they have changed (a conditional
GET, which is what the Bureau asks for). Every station is read out of the file — never a hand-typed
list — and kept in the `station` table so a restart knows where the stations are before the first
poll; the latest values live in memory. On every reading the nearest station's values ride beside the
model's, with the distance and the time; where the station is inside the hexagon, they *are* the
reading's "now".

A compact ledger — one row per station every six hours, holding the day's rain to 9 am and the running
maximum — is kept in `station_sample` for the drought maths (§1.6). Stations never write history.

The same server carries the warnings: one listing per state, read every five minutes, and the product
each item points at, read once per issue. A warning names the public weather districts it covers, a
station names the district it sits in, and that is the join: a hexagon's warnings are those covering
its nearest station's district. A fire weather warning is flagged on the fire picture.

The Bureau paused some feeds during its platform upgrade in September 2026; each file is checked live
before it is relied on, and a state whose file does not answer contributes no stations until it does.
`WEATHER_CONTACT` is sent in every request, as the Bureau asks.

## 1.5 The CFS

The official AFDRS rating per fire ban district — the rating, its Fire Behaviour Index and the total
fire ban flag, for today and four days — is read hourly from the CFS GeoHub (without the district
geometry: 32 KB against 6.4 MB) and put on every reading beside the indices computed here. Published
beats derived: a reader sees both and knows which one the public were told. The fifteen district shapes
are read once a day from the CFS's published file, so a hexagon's district is a point-in-polygon test
here rather than a query per hexagon.

Grass curing has no open feed (the GeoHub was checked on 18 September 2026 and carries no curing
layer), so it is entered per district on the console each week in fire season, with the date. It is
carried on every active hexagon in the district; a hexagon with no curing figure has no grassland index.

## 1.6 Drought

The fire indices need a drought factor; a drought factor needs a soil moisture deficit; a deficit
needs a year of daily rain and maximum temperature integrated into a Keetch–Byram index and then a
Griffiths factor. The drought is the hexagon's, like everything else the hexagon holds — at 32 km a
hexagon is already the scale a drought factor describes — from the Bureau stations inside it (or the
nearest within 75 km).

Starting a hexagon's drought fetches only the days the stations do not cover: running for 30 days and
needing 365, it asks Open-Meteo's archive at the hexagon's centre for the year behind the ledger and
nothing else — one fetch, about six units, once per hexagon. After that it is free: every day after
9:10 am in the hexagon's zone, each hexagon whose last complete day is behind the calendar is stepped
forward from the ledger, exactly once. The state is kept on the hexagon's row so a quiet hexagon picks
up where it left off. A reading with no drought state has no fire index.

River discharge stays keyed on the river model's own smaller cells — 5 km, because a river is a line —
from GloFAS, once a day per cell while a hexagon in it is active. Antecedent rain comes from the same
daily series the drought uses; forecast rain from the reading's own series.

## 1.7 Terrain and land use

Two rasters on the service's own volume, mounted and never committed: Geoscience Australia's 9-second
DEM for elevation, and ABARES' catchment-scale land use (or Geoscience Australia's land cover) for
what a place is. `GeoTiff` reads them without a GIS library — classic and BigTIFF, stripped or tiled,
uncompressed, Deflate, LZW or PackBits, geographic or Australian Albers. When a hexagon is created its
elevation is read at the centre, its mean slope from a lattice across it, and its land use counted
over the same lattice; the point's own class is read too. Without a file, the hexagon carries the
upstream model's own elevation, no slope and no land use.

Land use decides which index leads: mostly trees and scrub, the forest index; mostly grass and crop, the
grassland indices; mostly water or built-up, both are carried and `appliesToPct` says how little.

## 1.8 The fire picture

An *active* hexagon — one someone has asked about — carries the whole set, computed from what it holds
and replaced whenever any input changes: the McArthur forest index (FFDI) and grassland index (GFDI),
the AFDRS grassland model's Fire Behaviour Index and rating (the CSIRO grassland fire spread meter with
the Cruz curing function, on the published constants, with the intensity-to-FBI table), the official
district rating and total fire ban, the wind now and its next change within 48 hours, the drought
index, the warnings, the land use and which index leads, and the forecast indices day by day with the
deficit carried forward through the forecast's rain. The map colours by any of it.

The forest fire behaviour model of the AFDRS is not here yet; the doc that catalogued this rebuild
puts it after the grassland model has been checked against a bad day's published number.

## 1.9 History

A snapshot of a hexagon's current conditions and fire picture — never the forecast — is written to
`reading_snapshot` when an ask about the hexagon carries a `ref` — what the reading is for: an
incident id, a job number, a planning exercise, anything the caller names — at most once every three
hours per hexagon. This is a weather service, not an incident service: a reading is asked for for any
reason, and the ref is the caller's word for it. Only hexagons asked about with a ref have history;
nothing is ever deleted from the table by the service; a nightly export goes to the backups volume.
Asking the API for a past time returns the snapshot nearest that time, with its own time, or says
there is none.

## 1.10 The console map

The map draws every hexagon held, and by default what makes each one active: amber where a forecast
is held because something asked — fading as the forecast ages towards its expiry, so a hexagon nobody
is asking about any more is visibly going — blue where a Bureau station sits in it, a purple ring where
the drought has been stepped for its area, a faint outline where there is nothing yet; a switch shows
the forecasts alone. On every hexagon
that knows its weather, an arrow the way the wind blows, its length by the speed (from zoom 7), and
the temperature, humidity and speed as a label (from zoom 9). A tooltip carries the values and the
fire indices; a click opens everything held. The same select colours by any one value — the indices
in their rating colours, the rest on a ramp — and the time slider shows the layer as it was, from the
snapshots. The map never fetches; the probe on a point asks, and says so. The page is locked to the
device: the bar collapses on a phone, and only the map zooms.

## 1.11 What is deliberately not here

The decision about *when* to ask stays with the caller (The Hub's D-249: the stagger across what it
watches, the re-ask on a change, the per-tick ceiling). This service knows nothing about what a reading
is for beyond the ref the caller attaches; it holds the cache, the caller holds the question. There is
no Hub-side cache and no Hub-side fallback (D-252): what this service does not answer is asked about again.
