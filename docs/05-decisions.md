# 05 · Decisions

[← Docs index](README.md)

Three decisions produced this repository and its contract with the Hub. They were taken in the Hub, in
the style of its `docs/16-decisions.md`, and are quoted here verbatim rather than paraphrased. Below
them, the decisions this service took for itself: the three from the split, which the overhaul
superseded and which are kept for the record, and the ones the overhaul took.

---

## Taken in the Hub

> **D-242 · Three applications, three databases, HTTP between them.** Hub 8080, Operations 8081,
> Weather 8082. One Postgres instance to start, one database each. No cross-database reads. Placement
> (one VPS or three) is configuration, never architecture.

> **D-249 · Weather holds the cache; the Hub holds the question.** `WeatherManager`'s judgement about
> *when* a point is worth asking about stays in the Hub. The anchor cache, the provider chain, the
> budget governor and the drought accumulators go to Weather. The Hub calls every time and does not
> cache — until it does (§27.10).

> **D-252 · No fallback for either service. When Operations or Weather is not answering, or not
> configured, the Hub keeps ingesting every source and holds the work that needs the service until it
> answers: a channel is `PENDING` and asked again on the next read, a suggestion waits in an outbox
> and is posted on a later pass, an incident is owed a reading and every sweep asks for the owed ones
> first.** — James, 17 September 2026.

What D-252 asked of this side and the split did not give — an honest answer to "what was the weather
when the incident was raised" once the backlog is drained hours later — is W-6.

---

## Taken here, at the split (superseded)

**W-1 · Terrain from Open-Meteo's elevation endpoint, not a tile store** — superseded by W-8: a
mounted terrain file, read once per hexagon. **W-2 · Open-Meteo only by default; the others behind
config** — kept in spirit: `gully.upstreams.order` ships `open-meteo, google`, Google as the overflow.
**W-3 · No incident half; the sweep governs and sweeps** — the governor went (W-5); D-249 stands.

## Taken here, in the overhaul (19 September 2026)

### W-4 · Hexagons, not anchors; nothing pre-warmed

**The decision.** Every reading belongs to a 25 km hexagon on the Australian Albers plane, worked out
by arithmetic from one anchor and never stored until asked about; hexagons and only hexagons. A point is answered by its hexagon's reading; there
is no search for a nearby reading, no radius, no time tier, and no grid of readings kept warm over
the state.

**Why.** The free allowance spread over the country is about a hundred cells — far too coarse to be
useful — and readings are only wanted where something is happening. A tessellation gives a fire ground
a stable, shareable key for the whole of its life and lets a large fire be several hexagons, each
fetched once. **What it costs.** A point near an edge is answered from its own hexagon's centre, up to
8.7 km away, rather than from a nearer reading held for the hexagon next door. That is the same
distance the old reach allowed, and it is honest about which reading it is.

### W-5 · A budget, a breaker and a pacer; no governor

**The decision.** Per upstream: a budget counted from the ledger table against the published
allowance at a 90% guard, a breaker that opens after three consecutive failures or at once on a
refusal that names its window, and a pacer at the per-minute limit. Google Weather is the overflow.
The governor — six hundred lines that tied *how much we spend* to *how accurate we are* — is deleted.

**Why.** Its own comments said it almost never moved, it forgot its position on restart, and the two
dials it coupled are different dials. Spend is bounded by the budget; accuracy is a property of the
hexagon and the upstream, not of the day's spend.

### W-6 · History is written for asks that carry a ref, at most every three hours

**The decision.** A snapshot of a hexagon's current conditions and fire picture — never the forecast —
is written only when an ask carries a `ref` — what the reading is for, in the caller's words — and not
again for that hexagon inside three hours. Stations never write history. Nothing is ever deleted from
the table by the service.

**Why.** The question D-252 left open is "what was the weather at this time", and it is asked about
something the caller can name. A station updating every ten minutes would write eight thousand rows a
day for nothing anyone would read; a snapshot per ref-hexagon per three hours is a few hundred a season.
*Amended 19 September 2026: the ask named an incident when this was taken; it names a ref now, because
this is a weather service and a reading is asked for for any reason. The Hub passes its incident id as
the ref.*

### W-7 · A forecast lives three hours, five when the allowance is tight; a station's "now" beats a model's

**The decision, as rewritten on 19 September 2026.** A forecast is kept for a hard cap from its fetch —
three hours, five once the day's allowance is 70% spent, read live — and inside that life the model's
"now" is its series read at the moment. Where a Bureau station sits in the hexagon, its values are the
reading's "now", the upstream is asked for the series only, when a forecast is wanted, and the station
can throw the forecast out early (W-12). *As first taken* the reading was current until the end of
the quarter-hour Open-Meteo's current block covered and the series for an hour after its fetch.

**Why.** The upstream's own expiry was the model's cadence, not a measure of whether the forecast was
any good, and it re-fetched every hour whether or not anything had changed. A cap the budget can
stretch is the one lever that matters when the allowance is running out. A station's values are
values, not a reading of a different grade, and they are free every ten minutes.

### W-8 · Terrain and land use from mounted files, read once (the fallback changed by W-15)

**The decision.** Elevation, slope and land use come from GeoTIFFs on the service's volume, read by a
reader written here (classic and BigTIFF, stripped or tiled, four codecs, two coordinate systems) once
when a hexagon is created. Without a file, the hexagon carried the upstream model's own elevation and
no land use; since W-15 it reads both on its first ask, from Open-Meteo and Digital Earth Australia.

**Why.** No upstream call, no expiry, works offline, and a finer file replaces either without changing
anything else. A GIS library for one method — the value of one cell at a point — would have been forty
megabytes of jar.

### W-9 · The grassland maths lives here, with the curing

**The decision.** The McArthur grassland meter moved here from the Hub, and the AFDRS grassland model
(CSIRO grassland fire spread meter, Cruz curing function, the published intensity-to-FBI table) sits
beside it, both on one set of formulas tested against worked examples. The curing figure is entered on
this console per district; the official rating is fetched here. The Hub's metrics component carries
the reading's fire block whole.

**Why.** The service that holds the curing is the one that computes the grass index; two copies of the
1980 maths in two repositories were one too many. The forest fire behaviour model of the AFDRS waits
until the grassland one has been checked against a bad day's published number.

### W-10 · Plain SQL and Flyway; no entity manager

**The decision.** The schema is `V1__gully.sql`; the rows are records read through `JdbcClient`;
there is no Hibernate. The four platform tables the old service's Hibernate built are declared
`IF NOT EXISTS` so the deployed database migrates in place.

**Why.** Start-up time (the service starts in about two seconds), one place the schema is written, and
nothing that silently alters a table. The end-to-end test boots against the old schema to prove the
migration.

### W-11 · The drought is the hexagon's (reversed the same day)

**The decision, as taken.** The plane was tiled into fixed areas of seven hexagons — a hexagon and its
ring — and the drought state was per area, spun up once at the area's centre and shared, so that
neighbouring asks did not each pay the year of archive for the same rain (at 15 km, 51 asked hexagons
had meant 51 archive fetches).

**Reversed, 19 September 2026.** The hexagons grew to 25 km the same day, which is already the scale a
drought factor describes, and the areas were complexity for nothing: the drought is the hexagon's, like
everything else the hexagon holds — spun up once per hexagon at its centre, twenty-six units, stepped
daily from the station ledger for free, kept on the hexagon's row. `V4` dropped the areas' table. — James.

### W-12 · The station in the hexagon judges the forecast, on the four things it measures

**The decision.** On every ask, a hexagon with a station and a forecast compares the station's values
— the blend of its stations, where it holds several (W-13) — with the forecast read off its series at the same moment — temperature, humidity, wind speed
and rain since 9 am, each as station minus forecast and as a share of a tolerance (3 °C, 20 points,
15 km/h, 5 mm). The score is the worst of the four; at 1 the forecast is thrown out, the station stays
"now", and the days ahead are fetched again an hour later, not at once. Every comparison is written to
`forecast_drift`; `/api/v1/drift` and the map's drift view read it.

**Why.** A forecast's worth is how far it is from what the ground says, and only the station can say.
The worst of four rather than an average, because a fire index fails on any one input: humidity
twenty-five points wrong is a wrong index whatever the temperature. Direction, gusts and pressure are
left out — the first two are noise at a point, the third is not what fire turns on — and anything a
station does not measure cannot be judged. The hour before a re-fetch is what stops a model that is
simply wrong today from being fetched every ten minutes at five units a time. **What it costs.** A
hexagon without a station is not judged at all; its forecast lives out its cap. — James, 19 September 2026.

### W-13 · "Now" from the ground first: the stations in the hexagon blended, else the neighbours brought to its elevation, by inverse distance and lapse rate

**The decision.** "Now" is measured before it is modelled. A hexagon with one station answers with
it, unmoved. A hexagon with several stations blends them: each weighted by the inverse square of its
distance from the centre, its temperature and dew point first brought to the hexagon's mean elevation
by the lapse rates (−6.5 °C/km for temperature, −2 °C/km for dew point), humidity recomputed from the
two, wind averaged as a vector. A hexagon with none takes the same blend from its neighbours — two or
more of the six around it, else six or more of the eighteen in two rings — brought to its elevation.
Only then does the model stand in, and the reading says so (`currentFrom`, `nearby`). Elevation is
the hexagon's mean over a lattice of points, read on the first ask from Open-Meteo's elevation model
when no terrain file is mounted, because the neighbours' values are brought *to* it.

**Why inverse distance with a lapse rate, and not a Kalman filter.** A Kalman filter estimates a
state through *time* from noisy readings of it; it is the right tool for smoothing one station's
series or fusing a model run with observations as they arrive. The question here is spatial and
instantaneous — the value *here* from values *there*, now — and the standard answer to that for
surface weather is to weight by distance and correct for height. The height correction is the part
that matters: a ridge 600 m above the plains is 4 °C cooler and noticeably wetter than the plains'
stations say, and averaging their humidity would miss it entirely; recomputing humidity from the
adjusted temperature and dew point does not. Wind is not corrected for height — its dependence on
height is exposure, not lapse — nor is pressure, which the Bureau has already reduced to sea level.
The share rule (30% of a ring) is what stops one distant station speaking for a whole neighbourhood.
**What it costs.** A blend is an estimate, and is never allowed to judge a forecast (W-12); only a
measurement throws one out. — James, 19 September 2026.

**Added the same day: the reach.** A station counts for the hexagon it is in and for any neighbour
whose edge is within a quarter of the width (`Grid.STATION_REACH_KM`, 4.25 km at 17 km) — two hexagons
near an edge, three near a corner. Before this, the hexagon beside a town's station, with the
station a few hundred metres over its line, had nothing of its own and went to the neighbours'
blend; now the station is "in it" for both, and where a hexagon gains two such stations they are
blended at ring 0. The reach was a fraction of the width, not a distance, so it followed the grid (a fifth at first, a quarter the same evening) — until W-18 made it a distance the console turns.

### W-14 · Nothing on a timer: the sources are read on request, whole, when an ask finds them due

**The decision.** No source is polled. An ask for a hexagon reads the Bureau's station file for the
state the hexagon is in when that file has not been checked for fifteen minutes — a conditional GET,
and a file that is downloaded is processed whole, every station in the state — the state's warnings
at five minutes, and for a South Australian hexagon the CFS ratings hourly and the district shapes
daily. Elevation, land cover, the drought's spin-up and its daily step, the river discharge and the
forecast are each fetched on the ask that finds them missing or due, and the hexagon's picture is
drawn then. The only timers are an hourly sweep and the nightly backup. Every read that touched the
network is written to the ledger against the hexagon that caused it, and the map's sources panel
shows it.

**Why.** The service is asked about a handful of places at a time, and knowing about all of
Tasmania for three hours when nobody asks about Tasmania is work and traffic for nothing; the
Bureau asks for exactly this restraint. Reading the whole file when a file is read is not a
contradiction — one download serves every hexagon in the state for the next quarter of an hour, and
the alternative, a request per station, is the traffic the Bureau does not want. **What it costs.**
The first ask for a state after a quiet spell pays for the read, a few hundred milliseconds; the
station hexagons of a state nobody asks about are as old as its last ask, and the map says so. —
James, 19 September 2026.

### W-15 · Land cover from Digital Earth Australia, read once per hexagon on the ask; elevation from Open-Meteo the same way

**The decision.** With no land-cover file mounted, every hexagon's land use was blank. Now a hexagon
that is asked about reads its land cover from Geoscience Australia's DEA Land Cover (Landsat, 30 m,
one map per calendar year) — one WCS `GetCoverage` for the hexagon's box, a 96-pixel GeoTIFF of
level-4 class codes, counted into the seven classes over a lattice and kept with the hexagon so the
class at any point later asked about is read off it — and its elevation as the mean over a lattice
from Open-Meteo's elevation model. Both once, both kept on the row; a mounted file, when there is
one, still wins.

**Why DEA and not OpenStreetMap or a global product.** It is the national product, it covers the
whole country at 30 m every year, its level-4 code carries the woody-or-herbaceous and canopy-cover
distinction the two fire indices actually need, and its WCS answers a hexagon in one call of a few
kilobytes with no key. OpenStreetMap's land use is sparse in the country that burns; the global
10 m products need a download of the continent or a licensed platform. **What it costs.** One call
per hexagon, ever, on a service that is not ours; a hexagon asked about while it is down carries no
land use until the next ask a quarter of an hour later, and its indices lead by nothing. The mapping
from a hundred level-4 codes to seven classes is a judgement, written down in `DeaLandCover` with the
reasons, and a wetland's class follows its lifeform because reeds and paperbarks both burn dry. —
James, 19 September 2026.

### W-16 · A station's last hour is kept, and a change of wind in it is called out unasked

**The decision.** Every station's last six readings — an hour of the Bureau's ten-minute files —
are kept, in memory and in `station_recent` across a restart. From them a wind change is read: the
swing of direction graded slight, marked or sharp from 30°, 60° and 90°, the change of speed from 10,
20 and 30 km/h, the two together by the higher; a vane under 8 km/h is not compared. It rides on the
reading (`station.windShift`, with the readings), on the station point and on the hexagon, and the
map shows it without being asked: the station pulses and the hexagon's outline takes the grade's
colour whatever layer is up, with the swing, the speed and the two as layers of their own.

**Why.** A change of wind is the thing on a fireground that kills, and it is measured, not modelled:
the station saw it happen. The forecast's own wind change (§1.9) says when the model expects one; this
says the ground has had one, which is the check on that. Six readings because an hour is the window a
change happens in and the files come every ten minutes; kept in the database because a restart in a
fire season should not need an hour to see again. **What it costs.** Six small rows per station per
hour of reading, and a swatch of colour on a map that is otherwise calm. — James, 19 September 2026.


### W-17 · The wind as a trend: the five before, the latest, and the model an hour ahead, as one glyph

**The decision.** Beside the change W-16 flags, the map draws where the wind has been, is and is
going, at every fresh station, from zoom 8, as three arrows from one point: the mean of the five
readings before the latest (grey; a vector mean of direction, so 350° and 10° average to north, and
a calm is left out of it), the latest (black, or the grade's colour when the station has measured a
change), and the model's wind an hour ahead from the station's hexagon (amber, dashed, on top, so
where the model agrees its dashes ride the black arrow). A steady wind is one arrow; a change is a
fan; a calm is a dot. The station's tooltip and the hexagon's drawer give the same three in numbers,
with the model at one, three and six hours and the change the forecast expects when one is still to
come. `WindTrend` is the arithmetic; the station point carries it (`windMean*`, `windTrend*`,
`fc1h*`, `fc3h*`, `fc6h*`, `fcChange*`); a Trend toggle on the rail turns it off. The reading's
contract is unchanged: the trend is the map's, computed here from the readings the reading already
carries, and goes onto the API only when a consumer asks for it.

**Why.** W-16 answers "has the wind changed?" with a flag and a grade. The question on a fireground
is the one before it — "which way has it been, which way is it now, and which way will it be?" — and
the answer is three directions and three speeds side by side, not a colour. Five readings because
that is what is held behind the latest; the model an hour ahead because that is the arrow a crew
can act on, and the change it expects because that is the one thing the forecast says that the
ground has not yet. — James, 20 September 2026.

### W-18 · The station reach is turned on the map, kept across restarts, and the coverage it gives is drawn before anything is asked for

**The decision.** How far outside a hexagon a Bureau station still counts as the hexagon's own —
the reach, a quarter of the width since W-13's addendum — is no longer a constant. It is a value in
kilometres from a hexagon's edge, `Grid.DEFAULT_STATION_REACH_KM` (4.25 km) until the console sets
another, held by `Reach` and written to a `setting` table (V9) so the value the map was tuned to
survives a restart; the start log says which is in force. The map's rail gains a **Coverage** group
with a slider (0–30 km, a quarter-kilometre step) and **set**: dragging asks
`/console/map/coverage.geojson?reachKm=` — the same walk over every station that set will apply,
so the preview cannot differ from the result — and draws every hexagon any station would count for
at that distance, held or not, coloured by how many count for it (grey none, blue one, darker past
one) with the count in each cell from zoom 8, bold in a red ring past one, where "now" is a blend.
A second chip counts only the stations reporting. Set writes the value and re-links every hexagon
to its stations at the new distance (`HexagonStore.stationsChanged`), giving each station its
hexagons; nothing is reset, since no hexagon's id changes, and the hexagons a wider reach created
stay when it narrows. The walk itself (`Grid.cellsReaching`) goes ring by ring as far as the reach
can go — past a circumradius the second ring comes in, since a corner is exactly one edge's length
from a second-ring corner — rather than the six neighbours only. The layer carries
`stationsInReach` and `stationsReporting` per hexagon and `reachKm` in its meta. A lone station
within reach is still taken as it is, unmoved (W-13): at a wide reach a hexagon well above its
station reads the plains' temperature, and that is accepted — a reading is better than none, and
better than a call.

**Why.** The question the reach answers is "which hexagons have a 'now' from the ground without an
upstream call?", and the answer depends on a distance nobody could see the effect of without
editing a constant and rebuilding. Turning it on the map with the count in every cell shows the
coverage a distance buys — and, beside it, how much of that coverage is actually reporting, which
is the honest figure: a hexagon with a station within reach whose file has not been read for an
hour has nothing for free. The value is kept in the database, not the properties file, because it
is chosen by looking at the map, not by deploying. — James, 20 September 2026.

### W-19 · History is the ground's, not the hexagon's: the stations' six-hourly ledger, the model's stand-ins, and the drought's days, kept five years

**The decision.** The hexagon snapshots (W-6: a reading's conditions and fire picture, written when
an ask carried a ref, at most every three hours) are gone, table and all (V10). What the weather
*was* is answered from three records, none of them written on an ask:

- **The stations' ledger** (`station_sample`), the six-hourly row the drought already read, now a
  consolidation of the readings seen since the last row rather than the reading at its moment — the
  window's temperature extremes and mean, humidity extremes, wind mean and maximum, strongest gust,
  and how many readings made them — beside the values at the moment, the day's rain to 9 am and its
  running maximum. Kept five years (`History.KEEP`), pruned by the hourly sweep.
- **The model's stand-ins** (`model_now`): where a hexagon's "now" was the model — no station
  within reach reporting, no neighbours — the series read at the moment of each fetch is kept, so
  what stood in for the ground is not lost when the forecast expires. One row per hexagon per
  fetch; five years.
- **The drought's days** (`drought_day`): the daily rain and maximum a hexagon's drought was
  stepped with, and which source supplied each — the stations, the archive, the recent-days call —
  written as the drought reads them and read back before any source is asked. The archive is
  fetched once for a hexagon and never again; a reset hexagon spins up from the record. The one
  thing kept per hexagon, because rain is a place's, not a station's; five years.

A reading with `at=` is the hexagon's station's ledger row nearest the time, or the model's row
where the station was not there, within three hours either side (`History.STANDS_FOR`); the map's
timeline behind now draws the same. No fire picture is kept for a moment — the conditions and the
drought of the day are both there to recompute one from. `ref` is still accepted and carried on the
ask, and writes nothing. The nightly export writes the three records' day, one file each;
`gully.history.keep`, which nothing read, is gone.

**Why.** A snapshot of a hexagon was a copy of a station's reading taken when someone happened to
ask, keyed on the asker's reason — so a station reporting every ten minutes for a year left
nothing unless an incident sat in its hexagon, and the archive's year of rain, paid for at
twenty-six units a hexagon, was consumed and thrown away. The record that matters is the ground's:
what the stations measured, consolidated to the cadence the drought already needed; what stood in
when they were not there; and the rain a place had. Five years because that is the span a drought
comparison, or a fire season against the last, is drawn over; six-hourly because every ten-minute
reading for eight hundred stations is a size nobody asked for and a resolution nothing here reads.
— James, 20 September 2026.

### W-20 · The reading by name: `/now`, `/forecast` and `/drought`, beside `/readings`

**The decision.** Three routes beside `/api/v1/readings`, which stays as it is for the Hub.
`/now` is the reading cut to the ground's half — `current` and where it came from, the station and
the neighbours' blend, the fire picture drawn from it, the warnings, the station's word on the
forecast — with `forecast`, `drought` and `flood` null; `/forecast` is the whole reading, the days
and hours ahead with their indices, the drought and the flood picture, by a name that says what it
is. Both answer in the reading's shape under the one contract, so nothing new has to be kept in
step. `/drought` has a shape of its own (`gully/drought/1`): the deficit and the drought factor as
the reading carries them, where the inputs came from, the stations feeding the hexagon, the last so
many days of rain and maximum the deficit was stepped with — the record W-19 keeps, each day with
its source — and the rain those days add up to over a week, a month, a season and a year. The fire
picture rides on `/now` because the indices of the moment are computed from the moment's
conditions and the drought of the day; the flood picture stays on `/forecast` and `/readings`
because it is mostly rain ahead and the river's outlook.

**Why.** A caller that wants to know what the wind is doing should not have to read past seven
days of forecast to find it, and one asking how dry the country is should get the days behind the
number, not the number alone. The blocks already existed; the routes are the names the questions
have. — James, 20 September 2026.
