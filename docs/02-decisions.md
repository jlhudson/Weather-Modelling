# Decisions

One entry per decision, numbered W-1 onward from the start-over of 21 September 2026. The commit
that made each carries its number. Earlier decisions are in the git history before that date and
are not carried forward: the service they describe was deleted whole.

### W-1 · Start over: stations, South Australia, no hexagons

**The decision.** Everything the service had accumulated — the 17 km hexagons and their store, the
drought and its archive, the fire indices, the CFS feeds, the warnings, the wind-change and diurnal
derivations, the terrain rasters, the readings API and its contract, the history, eleven migrations
and six documents — is deleted, along with the data: the Postgres volume is dropped and the schema
starts again at a new `V1` (the Hub's API key is carried across so nothing has to be re-issued).
What stays is the platform (login, keys, diagnostics, the ledger), the two forecast clients and
their allowance page, and the Bureau's station file — for South Australia only, on a ten-minute
timer, every station in it held with its latest values in memory. The map draws the stations and
opens one on a click. The git log is kept; the decision numbering restarts.

**What it costs.** The Hub's `/api/weather` answers 404 until a point can be answered from the
stations again, which is after the reach (W-2) and the interpolation between overlapping reaches.

**Why.** The hexagons were the wrong unit: one 17 km cell over Adelaide, the Hills and the plains
towards Murray Bridge carried one answer for three climates, and every layer on top — the blend, the
drift, the drought's rings — was a way of arguing with that. The stations are the facts; what each
one speaks for should be a shape drawn from the ground, not a tiling drawn from arithmetic. Starting
from the stations, with nothing derived until the shape is right, is cheaper than unpicking. — James,
21 September 2026.

### W-2 · The reach: a polygon per station, drawn once from the terrain by a cost rule

**The decision.** Every station carries a reach — the ground it speaks for — as a polygon. The
terrain around each station is sampled once from the Terrain Tiles on AWS's open data registry
(the station and every kilometre out to 50 km on 48 bearings, 2,401 points off some fifteen tiles) by
a background job that takes one station every fifteen seconds, and kept. Open-Meteo's elevation
endpoint was the first choice and lasted six calls: it counts every point, so a station is 2,401
against a day's 10,000. The polygon is arithmetic over those samples under a rule
of two numbers, both sliders on the map: the *reach* (40 km) and *what a hundred metres of height
costs* of it (10 km). A ray stops where its distance plus the cost of the greatest height difference
it has crossed exceeds the reach — the greatest, so a ridge is a barrier — with a 3 km floor. The
sliders preview any rule on every station at once; *set* makes it the rule and a restart keeps it.
The clicked station's reach is drawn in cyan, every station's on a toggle; the drawer carries the
area, the range of the rays, why each stopped (as a rose), the model's height against the Bureau's,
and a *sample it now* for a station the job has not reached. `/api/v1/reach.geojson` is the same
collection for a caller.

**Why.** Distance alone made one answer for Adelaide, the Hills and the plains beyond; a hard height
cutoff would make a station count or not with nothing between. A cost trades the two smoothly, and
taking the greatest difference crossed rather than the difference at the point is what keeps the
far side of a ridge out. Sampling once and drawing on demand is what makes the sliders free to turn
and honest — the shape only ever changes when the rule does. Temperature and humidity are kept out
of the shape on purpose: they are what the polygon carries, and they belong in the interpolation
between overlapping reaches (with the lapse rate) and as a check on the rule, not in its geometry.
— James, 21 September 2026.

### W-3 · The ocean: a ray ends at the water, a coastal station is held to a limit

**The decision.** A ray ends at the water — the first terrain sample at or below sea level stops it
half a step short, so the shore is inside the reach and the sea is not. A station with water inside
10 km on any bearing is coastal, and every one of its rays is held to a third number on the rule, the
coastal limit, 25 km by default and a third slider beside the other two. Land below sea level counts
as water. The drawer says whether a station is coastal and how near the water is, the rose colours
the rays the water and the limit stopped, and a coastal station wears a thin blue ring on the map.

**Why.** A reach that ran out over Gulf St Vincent answered for water nobody stands on and, worse,
for the far shore. Maritime air is its own climate — a coastal station's afternoon is the sea
breeze's, cooler and damper than twenty kilometres inland — so a coastal station should reach along
the coast as far as any, and inland only as far as its air does; twenty-five kilometres is roughly
where a sea breeze gives out, and it is a slider because that is a guess to be looked at. Whether the
inland side should shorten smoothly rather than at a wall is the next thing to see on the shapes. —
James, 21 September 2026.

### W-4 · Rivers are not water: water is what is three kilometres across

**The decision.** A ray ends at water only where the water is at least 3 km across along the ray —
three consecutive samples at or below sea level. A river is a line and never is: the lower Murray,
which the tiles read at sea level below Lock 1, is crossed like any dip in the ground (its bed still
costs what its depth costs). The sea and the big lakes are areas and always are, so Murray Bridge and
Strathalbyn still end at Lake Alexandrina. *Coastal* is such water inside 10 km. One constant, no
slider.

**Why.** Pallamana was coastal to the Murray six kilometres away, every ray capped at 25 km, and
James asked for rivers to be ignored: a river has little or no effect on the weather beside it, while
the gulf plainly does. Width is the honest difference between the two, and three samples is the
narrowest the 125 m tiles can be trusted to tell. Telling a lake from the sea by depth is the next
refinement if a lake-side station ever reads as coastal wrongly; no South Australian station does
today. — James, 21 September 2026.

### W-5 · The probe: which stations speak for a point, laid out and not blended

**The decision.** A click anywhere on the map, and `/api/v1/stations/at?lat=&lon=` with a key,
answers with the stations whose reach contains the point — nearest first, each with its distance,
bearing, height above or below the point, what it last said and how old that is, and how far past
the point its ray goes — and the nearest three whose reach does not, with why their ray towards the
point stopped short. On the map: a crosshair at the point, cyan spokes to the stations in reach and
their reaches faintly, grey dashed spokes to the three outside. The point's own height is one read of
the elevation tiles, which are already cached. Nothing is interpolated.

**Why.** James wanted to see the stations within range of a point before any blending exists — the
ingredients before the recipe — so the membership rule can be judged on its own, and an omission can
be seen rather than wondered about. The endpoint is the one a reading at a point will grow on, and
the one the Hub's `/api/weather` can be pointed at when it does. The five ways to blend them — nearest
station, inverse distance, height-corrected inverse distance, Barnes successive correction, kriging
with an elevation drift — were laid out on 21 September and none chosen yet. — James, 21 September 2026.

### W-6 · The record, and the drought from it: six-hour windows, Bureau days, a year backfilled

**The decision.** A real station's readings are kept as six-hour windows (3, 9, 15, 21 local) and
as Bureau days — the 24 hours from 9 am, rain the published total to the next 9 am, maximum the
highest reading between — both 548 days. The days a station's year lacks are filled from
Open-Meteo's reanalysis archive by a background job, one station every fifteen seconds, only the
missing days, never over a day the station made itself. Each station's drought is the Keetch–Byram
deficit integrated from its own record — from field capacity at the start of the year behind
today, the mean annual rainfall from that year — and the Griffiths drought factor from the deficit
and the last twenty days of rain; both on demand, memoised, on the stations feed and the detail. The
formulas are the ones this service had before the start-over, restored from git with their tests
(Keetch & Byram 1968 in Crane's metric form; Griffiths 1999 as corrected by Finkele 2006), not
retyped. The drought rides the same reach as the current: one polygon per station, one membership.

**Why.** The deficit is a running total, not an observation: it has to be integrated from history,
and a year is the honest spin-up because a South Australian year always holds the wet season that
resets it. Interpolating each station's *index* rather than its inputs is what the Bureau's gridded
drought factor does and keeps the record where it belongs, with the station. Backfilling only the
missing days, and resting a station six hours between attempts, is what keeps a hundred stations at
a few hundred units a day at worst and nothing at best. A day without both a rain total and a
maximum is left for the archive rather than written half-known. — James, 21 September 2026.

### W-7 · The points of our own: a place nobody reaches becomes a station

**The decision.** A place no Bureau station's reach contains - or that only stations without a
temperature reach - is dropped as a station of the kind `point`: the same terrain, reach and rule,
its current from Open-Meteo (one hour's life), a year of record from the archive. A later ask inside
its reach reuses it and fills only what is missing; one unasked for 548 days is dropped again. It
has no six-hour ledger. On the map it is an amber diamond, never a dot.

**Why.** James asked that a point outside every station become a drought station of ours, updated
only when the next ask inside its polygon comes, its days backfilled rather than kept current. Making
it a station of a second kind, rather than a second machinery, is what lets the reach, the record,
the drought and the map treat it as one thing with one exception - where its current comes from. —
James, 22 September 2026.

### W-8 · The reading: height-corrected inverse-distance weighting, per value, over one polygon

**The decision.** A reading at a point blends the stations whose reach contains it: weights
`1 / cost²` with the cost measured along the ray as the reach is; temperature, dew point and the
day's maximum brought to the point's height by the lapse rate first; humidity, wind, rain and each
station's own KBDI and drought factor blended as they are; the wind's direction as a vector. A
station lacking a value stays out of that value's blend. The FFDI is computed from the blend and is
null when any input is missing. A click on the map asks for the reading; `/api/v1/reading` answers
a caller. Every value names the stations behind it.

**Why.** James chose height-corrected IDW from the five laid out on 21 September, and asked that the
drought use the same polygon as the current and that a station without the right values be left out
rather than guessed for. Taking the cost along the ray, rather than the bare distance, is what keeps
a station across a ridge from counting as much as one across the plain, and it costs nothing - the
terrain is already there. — James, 22 September 2026.

### W-9 · The wind as a trend at every station, and the whole of the file in the drawer

**The decision.** Every station carries, beside its latest wind, the mean of its newest five
readings — speed and gust as means, direction as a vector — with how many and over how long. The
map draws both from zoom 8 (the latest solid, the mean grey), the tooltip says both, and the drawer
lists them with the rest of what the file carries: pressure, dew point, apparent temperature,
visibility, cloud and oktas, delta-T, the day's maximum and minimum.

**Why.** James asked to see the wind now and a five-reading average at every station, and the
pressure and the other Bureau fields when a station is clicked. Five readings is fifty minutes of a
ten-minute file: long enough that a gust is not a change, short enough that a change is not lost.
— James, 22 September 2026.

### W-10 · No ocean borders: water ends no ray, the sea is sea level for the cost

**The decision.** A ray no longer ends at the water. The sea is ground at sea level for the height
cost and nothing more, so a station on an island or a headland reaches across the water to the far
shore as it would across a plain, and the small islands whose station sits a few hundred metres out
in the sea have weather again. Water is still seen: what is at least 3 km across along a ray (W-4)
inside 10 km of the station makes it *coastal*, and a coastal station is still held to the rule's
coastal limit on every bearing — the three sliders stay as they are. The rose has one colour fewer;
the drawer says the water is crossed, not a border.

**Why.** James: "there are a lot of small islands that have no weather due to simply having their
station in the ocean by a few hundred meters. so for now, NO ocean borders please." A border at the
sea was drawn to keep a reach off the water nobody stands on, but its cost fell on the stations
that matter most to the coast — a station in the sea reached nothing, and a headland's reached only
along its own spit. Overlap is allowed and the interpolation weighs by cost, so a reach that runs
out over the gulf does little harm: the far shore is 1/cost² away, and its own stations outweigh it.
The coastal limit does the work the border did, and it is a slider. — James, 22 September 2026.

### W-11 · Pressure as a colour; the direction comes with the wind, not with a button

**The decision.** *Pressure* (mean sea level, hPa, 990 to 1040) joins the colours a station can be
coloured by, and the tooltip says it. The *Wind* toggle is gone: choosing *Wind* or *Gust* as the
colour draws every reporting station's direction at every zoom - the latest as a solid arrow the
way the wind blows, its length the speed coloured by - and from zoom 8 the mean of the last five
behind it in grey, as before. Any other colour draws no arrows.

**Why.** James asked for pressure as a selectable colour, and for the direction to come with the
wind when it is chosen - "I don't need an additional wind button." A separate switch made the
arrows a second thing to remember, and drew them over a map coloured by something else; the
direction is part of what *wind* means, so it belongs to the chip. The mean arrow still waits for
zoom 8 because two arrows a station at the whole-state zoom is a thicket. — James, 22 September 2026.

### W-12 · The ocean border is back, except for an island: a station the water would take three quarters of

**The decision.** A ray ends at the water again, as W-3 had it, and a coastal station is held to
its limit as before. But a station the water would end at least three quarters of the rays of - 36
of 48 - is an *island*, and for it the water ends none: the sea is ground at sea level for the
height cost and nothing more, and the station reaches across it to the shore beyond. It is still
coastal, still held to the coastal limit. The share is one constant, `ISLAND_SHARE`, not a slider.
The drawer says how many rays the water ends, or for an island would have, and calls the station
an island; on the map an island's coastal ring is dashed. W-10 is undone but for the clamp it
introduced, which the island rule needs.

**Why.** James took W-10 back: he wants the ocean border on the polygons - a shore station should
not answer for the far side of a gulf - but not for the little islands, whose station a few
hundred metres out to sea had no reach at all. The two cases differ in how much of the station the
water would take: a shore takes half a station's rays, a headland two thirds, a jetty or an islet
nearly all. Three quarters is the line James named; on the South Australian stations it takes in
the two that stand in the sea - Neptune Island (48 of 48) and Black Pole (44) - and six headlands,
Cape Willoughby (44), Stenhouse Bay (42), Robe (42), Point Avoid (39), Thevenard and Warburto Point
(36 each), and leaves Edithburgh and Cape Borda (34) their borders. It is a constant, not a slider,
because the honest fix for a station on the wrong side of it is to look at that station, not to move
every one. — James, 22 September 2026.
