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
