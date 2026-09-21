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
