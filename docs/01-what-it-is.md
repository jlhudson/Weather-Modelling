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
rain since 9 am and to 9 am, the day's maximum and minimum, the sky — into `station_reading`, one
row per station per observation time (W-15). The latest and the newest six per station are held in
memory as well, for the feed and the wind's trend, and read back from the table at the start.

**Two timers, and nothing else on a clock** (W-15). The Bureau's file is read every ten minutes,
and once a day at 9:30 local - and once, a minute after the start - the housekeeping runs: the
fold of the stored readings into the six-hour history and the Bureau days, the pruning (readings
older than three days, windows and days older than 548, the upstream ledger, points of ours no
ask has used for 548 days), the terrain of any station lacking it, and the year of any Bureau
station missing days. Everything else - a reading at a point, a point of ours, its current and its
record, a station's drought - happens when it is asked for, so the service idles at the cost of
one small file every ten minutes, and Open-Meteo and Google are touched only by an ask or by the
morning's backfill.

A station is *reporting* when its latest observation is under seventy minutes old. Beside what it
last said, every station carries its wind as a trend (W-9): the mean speed, gust and direction (as
a vector) of its newest five readings - about fifty minutes of the ten-minute file - so a swing shows
against what the wind has mostly been. When the map is coloured by the wind or the gust (W-11) every
reporting station wears its direction, the latest as a solid arrow the way the wind blows, its
length the speed, and from zoom 8 the mean behind it in grey; the drawer lists them with everything else
the file carries for the station - pressure, dew point, visibility, cloud and oktas, delta-T.

## 2. The reach

Each station carries a polygon — its reach — which is the ground it speaks for.

**The terrain, sampled once.** For each station, 7,201 points of a digital elevation model: the
station itself, then every kilometre out to 150 km along 48 bearings, 7.5° apart (50 km before W-19). The model is the
Terrain Tiles on AWS's open data registry (Mapzen's, from SRTM, GMTED2010 and others, with
bathymetry over the sea): public, no key, no published limit, 256-pixel PNG tiles at zoom 10 —
about 125 m a pixel here — decoded with the JDK. A station's 150-kilometre disc is about a hundred
tiles, neighbouring stations share them through a cache, and every tile fetched is a
row in the ledger. The daily housekeeping (W-15) samples every station lacking terrain, so a new
station in the file is picked up on its own; the console can sample one station now from its drawer. (Open-Meteo's elevation
endpoint was tried first and counts every point as a call: 2,401 a station against 10,000 a day.) The samples are kept in `terrain` (eight-byte doubles, the station's own first) with the
position they were taken at, so a station that moves is sampled again.

**The rule, and what the ground costs.** *Reach* — how far a station reaches over flat ground,
40 km by default — and *what a hundred metres of climb costs* of that reach, 10 km by default.
Along each bearing a ray walks out a kilometre at a time and stops when
`distance + kmPer100m × (climb + descentShare × descent) ÷ 100` exceeds the reach, where *climb* and
*descent* are the greatest sustained rise above and fall below the station crossed so far. Two
things make that sentence what it is (W-16):

*A barrier has to hold.* Ground counts only where it keeps its height for 3 km — three samples in a
row, the same rule the water has. A gully one or two kilometres across is crossed for nothing, so a
ray along the dissected Mount Lofty ridge keeps going instead of dying in the first valley; the
scarp, four hundred metres up and staying up, is a wall as it was. A barrier costs from the step it
begins at, not from the step the ray has seen three of, so a ray stops at the foot of the wall.

*Climbing costs more than descending.* The slider is the price of climbing; descending costs a share
of it, half by default, on a fourth slider. The plain's air does not climb the scarp, but the hills'
air drains to the foothills — the gully wind this service is named for. The two are counted apart
and added, so a ray that climbs a range and drops beyond it pays for both. Before this, one number
had to be both, and there was no setting at which Adelaide stopped at the scarp and Mount Lofty
still spoke for the ridge.

A ray cut by height still reaches 3 km, so every station has some ground. The polygon is the 48 ray ends joined. Nothing about it is stored: it is arithmetic over
the terrain in memory, so the four sliders on the map preview another rule on every station at once,
and *set* makes it the rule (kept in `setting`, so a restart keeps it). What this does around
Adelaide at 35 km, 5 km per 100 m and half: West Terrace reaches 13 km east - the foothill scarp -
and 30 km north along the plain; Mount Lofty (692 m) 17 to 27 km along the ranges; Mount Barker
(354 m) 22 to 31 km.

**The sea, a fourth number.** A ray ends at the water: the first sample of it (the tiles carry
bathymetry, so the sea is negative and the shoreline zero) stops it half a step short, so the beach
is inside and the sea is not. Water is water only when it is at least 3 km across along the ray —
three samples in a row at or below sea level (W-4): a river is a line and never is, so the lower
Murray, which the tiles read at sea level, is crossed like any dip in the ground; the sea and Lake
Alexandrina are areas and always are. (A station with such water inside 10 km used to be *coastal*,
held to a shorter coastal limit on every bearing; W-19 removed it.) Land below sea level reads as water too (Lake
Eyre, at minus fifteen), which for a reach is right: a salt lake is not the station's ground.

Unless the station is an *island* (W-12): where the water would end three quarters or more of its
rays — a station on a small island, or a few hundred metres out on a jetty — the water ends none of
them. The sea is then ground at sea level for the height cost and no more, and the station reaches
across it to the shore beyond as it would across a plain. A station on a bordered coast keeps its border; one the sea would take
everything from keeps its reach. The drawer says how near the water
is, how many rays it ends, and whether the station is an island; an island wears a dashed blue
ring on the map.

**Inland, further** (W-19). A station's reach grows with its distance from the sea: the rule's
reach, and a share of it again for every hundred kilometres inland - 20 % by default, a slider from
0 to 50 - to at most 150 km, what the terrain is sampled to. At 40 km a station on the coast
reaches 40, one 300 km inland 64; so the outback's few stations speak for the wide country between
them. The distance is to the sea, found once in 64 coarse tiles (zoom 7, about a kilometre a pixel,
over the state and the ocean below it) as the water joined to the ocean - the gulfs are sea, Lake
Eyre and the salt lakes are not - and kept with the station's terrain (`terrain.inland_km`).

What this does around Adelaide: West Terrace (29 m) reaches the plains north and south, stops at the
foothill scarp, never crosses to the Hills, and ends at the gulf; Mount Lofty (700 m) keeps the ridge
and not the plain; Murray Bridge (30 m) reaches east over the flat and stops short of the Hills to its
west. Two reaches may overlap — a point inside several is for the interpolation, which comes next.

## 3. The record and the drought

**The readings, stored.** Every observation the Bureau's file brings is written to
`station_reading` as published (W-15): the database is the store, and what lives in memory is the
one cache the service keeps - each station's latest and its newest few, for the feed and the wind's
trend, read back from the table at the start. A reading is kept three days; the history below is
what it becomes.

**The record, folded once a day.** The daily housekeeping folds each Bureau station's stored
readings into the Bureau day that ended at the last 9 am: four six-hour windows ending 3 pm, 9 pm,
3 am and 9 am local, written to `station_hour6` (readings, the temperature's extremes and mean, the
humidity's extremes, the wind's mean and maximum, the strongest gust, and the Bureau's running
figures as they stood at the window's last reading), and the day into `station_day`: dated by the
9 am it began at, its rain the total to 9 am that the first reading at or after 9 am publishes, its
maximum the highest of the day's windows and the Bureau's running maximum (6 am to 9 pm) as it stood
at 9 pm, which catches an afternoon peak that fell between two readings. A day
without a total, or without a reading in each of its four windows - a restart at dusk, a station
silent till evening - is left absent for the archive to fill, whose maximum is the whole day's. The fold is
idempotent and looks back three days, so a run that was missed is caught up by the next. Both
tables are kept 548 days.

**The backfill.** The daily housekeeping asks Open-Meteo, one Bureau station after another, for
the days its year lacks: the reanalysis archive (hourly rain and temperature, folded into 9 am days;
about 26 units for a year, one per fortnight) for the days older than the archive's six-day lag, the
forecast endpoint's past days (one unit) for the rest. Only the missing days are asked for, a gap
of three or fewer is not worth a call, and a station is left six hours between attempts. The
archive's day never replaces a day the station made itself. Between runs, an ask fills what it
needs (W-14): a member station with no drought to give, a point of ours' missing days.

**The drought.** Each station's KBDI is integrated from its own record: from field capacity at the
start of the year behind today, day by day, with the mean annual rainfall the run needs taken from
that same year; the drought factor from the deficit and the last twenty days of rain, today's so
far last. Twenty days is the least a record can speak from; a year less a fortnight is complete.
Nothing is stored and nothing is memoised - it is arithmetic over the record, a millisecond a
station, done when asked - and it travels on the stations feed (`kbdiMm`, `droughtFactor`) and the station's detail, with the
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
stations behind every value. A member with no drought to give - its record too short to compute
one - has its missing days fetched then and there (W-14), once per six hours per station, so the
next ask has it. The forest fire danger index (McArthur Mk 5, Noble, Bary and Gill 1980)
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
folding its fetches; and nothing of a point moves on a timer (W-14) - its current and its missing
days are fetched when an ask lands in its reach, and only then. On the map a point is an amber diamond, filled with its value like a station.

**A forced ask** - the *force grab* pill in the reading's drawer, or `&force=true` on the route (W-13) -
goes to the upstreams first, whatever the timers say: the Bureau's file is read now, the days each
station in reach is missing are filled (any missing day, rest or no rest), and a point of ours has
its current fetched again however young it is. The reading then carries `grabbed` - whether a new
Bureau file came, whether the model's current was fetched, how many days of record were filled -
and the drawer says it in a line. It is the same reading, just fetched first; nothing is guessed.

**The forecast** (W-20). A reading carries the next twelve hours and three days from Open-Meteo
(Google behind it): the forecast of the nearest station whose reach contains the point, or of the
point of ours where none does. Forecasts are kept a station at a time in `station_forecast`, and an
ask that finds one missing or older than three hours fetches it again - a forced ask whatever its
age; nothing fetches on a clock, and rows older than a day are pruned. Clicking a station is an ask
for its forecast too. A point of ours' current and its forecast are one fetch.

**The fire outlook** (W-22). Every forecast hour carries McArthur's forest fire danger index from that
hour's own temperature, humidity and wind - never the day's maximum, minimum and maximum put together,
which are rarely one hour and overstate the day - and each day carries its worst hour, with that hour's
values. The drought factor turns at 9 am as the Bureau's does: the hours of the day now running take the
station's drought as it stands; each later day takes the deficit stepped through the day before with the
forecast's rain and heat, and the factor recomputed with the rain window moved on, so a wet day ahead
lowers the next day's index. The drought is the forecast station's own, or the nearest station in reach
that holds one, and the answer names it; without one there is no index.

**When the Bureau goes quiet.** A station in reach whose latest reading is older than seventy
minutes - its file down - has the model's now fetched for it (the same forecast, fetched again when
its now is over an hour old), and that stands in: blended into the reading and drawn on the map with
a dashed amber ring, labelled *model* everywhere, and never written into the station's readings or its
history. So a point of ours is dropped only where no station reaches, not where they have gone quiet.

**The fire ban district** (W-23). Every station and every reading carries its fire ban district - one of
the CFS's fifteen, by point in polygon against the CFS's own file (read when asked, held a day, holes
kept) - and what the CFS has published for it: the AFDRS rating, its Fire Behaviour Index and the total
fire ban, for today and the days ahead, read when asked and held an hour. Out of season the feed keeps
the last day it published; every day carries its date, and a day before today is never given as
today's - the answer says there is no rating for today, and when the last one was. The map draws the
districts on a toggle, each in the colour of today's rating.

**The grass fire danger** (W-24). Grass curing - how dry the grass is - and the fuel load are entered per fire
ban district on the console's Curing page from the CFS's weekly curing map; no open feed publishes them
(4.5 t/ha, McArthur's standard, where no load is known). With them every reading and every forecast hour
carries McArthur's grassland index (Mk5, Noble, Bary and Gill 1980) and the AFDRS grass model (CSIRO,
Cheney, Gould and Catchpole 1998) with its Fire Behaviour Index, rating, rate of spread and flame height;
each forecast day carries the worst hour of each. Without a curing figure the grass block says so and
carries no index; a figure older than a fortnight is used and marked old. The curing is the operator's
entry, not the weather: the admin reset keeps it.

**The warnings** (W-25). The Bureau's South Australian warnings - fire weather, severe weather, severe
thunderstorms, floods - from the state's listing and each product it names, read when asked and held ten
minutes. A warning carries every area it covers; a reading is under it when it names the public district of
the nearest Bureau station in reach (or the nearest at all) or the point's fire weather district, and every
other warning in force in the state is listed beside it by title, so a flood warning filed by river basin
is never dropped for want of a district to match. The map's side panel carries a banner while any is in
force; `/api/v1/warnings` lists them.

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
Open-Meteo's forecast costs three units; a point of ours fetches one when asked, and the archive
and the recent days when its record is missing them.

## 7. The console

One login (`operator`, an 8-digit code, lockout after five wrong tries). The map draws every station
where it is, filled when it is reporting and hollow when it is not, coloured by what it last said;
a click opens everything held for it. The legend under the side panel is the colour's scale with
the stations' distribution on it as bars, over the stations in view or every one held (W-17); hover
a bar and its stations are lit on the map, hover a station and its bar is lit. A click anywhere else is an ask from outside, made the way
The Hub makes one (W-14): through the API's front door with the console's own key - a key issued
to the consumer `console` with the readings scope, carried on the map page, listed and revocable on
the API keys page like any other, issued again on the next map page if revoked - so the flow the
operator watches is the flow a consumer gets, rate, access log and all. The Upstreams page is the
allowance table, the spend chart, the breaker history, the Bureau's file and the recent calls. Diagnostics is the log signatures with the
startup record. API keys issues and revokes keys with a scope. Admin (W-18, for testing) deletes every station,
reading, window, day and terrain and starts again from the Bureau's file.

## 8. The API

`/api/v1/stations.geojson` and `/api/v1/reach.geojson` are what the map draws; `/api/v1/stations/{id}`
is what a click on a station opens and `/api/v1/stations/at?lat=&lon=` what a click anywhere else opens; `/api/diagnostics` is the shape The Hub's morning agent reads. Every route needs a key.

## 9. Storage

Twelve tables: `api_key`, `console_user`, `api_access_log` (kept thirty days), `log_event`, `setting`
(what the console sets and a restart must keep), `upstream_call`, `station` (`V1`), `terrain` (`V2`;
`inland_km` since `V6`), `station_hour6` and `station_day` (`V3`), `station_reading` (`V5`) and
`station_forecast` (`V7`). Held in memory as well, and read back at start: the stations with their
latest readings, their terrain, their days and their forecasts.
