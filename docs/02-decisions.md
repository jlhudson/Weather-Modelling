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
