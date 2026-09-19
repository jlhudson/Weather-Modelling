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

**The decision.** Every reading belongs to a 32 km hexagon on the Australian Albers plane, worked out
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

### W-7 · The upstream's own expiry, and a station's "now" beats a model's

**The decision.** A reading is current until the upstream says otherwise — the end of the quarter-hour
Open-Meteo's current block covers — and the series until an hour after it was fetched. Where a Bureau
station sits in the hexagon, its values are the reading's "now" and the upstream is asked for the
series only, when a forecast is wanted.

**Why.** Thirty fixed minutes was neither the model's cadence nor the station's. A station's values are
values, not a reading of a different grade, and they are free every ten minutes.

### W-8 · Terrain and land use from mounted files, read once

**The decision.** Elevation, slope and land use come from GeoTIFFs on the service's volume, read by a
reader written here (classic and BigTIFF, stripped or tiled, four codecs, two coordinate systems) once
when a hexagon is created. Without a file, the hexagon carries the upstream model's own elevation.

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

### W-11 · Drought areas are a fixed tiling of seven hexagons, one spin-up each

**The decision.** The plane is tiled into fixed areas — a hexagon and its ring, seven cells, three across,
about 100 km, on a lattice so the areas never overlap and never move — and the drought state is per area:
spun up once at the area's centre by whichever hexagon in it is asked about first, stepped once a day
for all of them, held in `drought_area` and copied onto each hexagon's row. The radius is one
constant; 2 would make nineteen-cell areas about 160 km across.

**Why.** The inputs were already pooled over the ring, but the state was per asked hexagon, so
neighbouring asks each paid the year of archive — 51 asked hexagons, 51 archive fetches — for
what was the same rain. An area anchored to the first hexagon hit would have overlapped its
neighbours and depended on the order of asks; a lattice does neither. **What it costs.** The rain
gradient inside 100 km — the Adelaide plains against the Mount Lofty Ranges — is one figure, as it was
already for the inputs. — James, 19 September 2026.

