# 06 · Overhaul suggestions

[← Docs index](README.md)

A restart of the Weather service, keeping the goals and the rough setup. Each suggestion is a
title and a short description of what would change and why. Item 0 is what James asked for; the
rest were to pick from. **All of it was built on 19 September 2026** — every item, 0 to 19 — and
this file is kept as the record of what was asked and why. What runs now is described in
[01-what-it-does.md](01-what-it-does.md); the decisions it took are W-4 to W-10 in
[05-decisions.md](05-decisions.md). Three details the build settled: the forest fire behaviour
model of the AFDRS waits, as item 4 says, until the grassland one has been checked against a bad
day; the CFS GeoHub was checked on 18 September 2026 and publishes no curing layer, so item 18 is
the console entry; the seven station files and seven warnings listings were verified live on
`reg.bom.gov.au` the same day, and a warning reaches a hexagon through its nearest station's
public weather district.

**Code name: Gully** — the Adelaide Hills gully wind, a local fire-weather phenomenon; short,
distinct, and used by nothing else in the fleet. Alternatives if it does not land: Northerly (the
hot north wind behind South Australia's worst days), Willy (willy-willy), Squall. The repository
and the service take the name once chosen; The Hub's `HUB_WEATHER_URL` does not care.

**What the service is for.** It answers "what is the weather, and the fire danger, at this point,
now (or at this time)" for a handful of websites — most of them asking through The Hub — some of
which will ask about anywhere in Australia. It should answer from memory, spend the free upstream
allowance carefully, and return one clear reading that is trusted — no confidence scores, no
"estimate" or "actual" branching. The Hub asks, Gully answers, and the values are the values.

**What stays.** Java and Spring Boot; one Postgres; Open-Meteo as the free upstream, Google Weather
as its overflow, with room for others; the fire-weather maths, which is correct and tested; the console behind one login; keys on
the API; a missing value is missing, never zero; every answer carries its source and its time.

Size: **small** a day · **medium** a week · **large** two to three weeks · **very large** a month
or more.

---

### 0 · Requested — the todo list

- [x] **Stop writing to the database every time a cached reading is served.** The code updates a
      "times used" counter in the database on every hit. The counter is only used to decide which
      readings to drop first when memory is full, and it already lives in memory. Keep the counter
      in memory, delete the database write. Small.
- [x] **When several requests arrive at once for a place we have nothing for, fetch it once, not
      once per request.** Today ten simultaneous requests for a new place make ten upstream calls
      and store ten copies. Small.
- [x] **Use the upstream's own expiry time.** Open-Meteo tells us when a reading goes stale; we
      store that and ignore it, using our own fixed 30 minutes instead. Use theirs. Small.
- [x] **No pre-warmed grid over the state or the country.** Readings are fetched only where
      something is happening (item 1).
- [x] **Large fires get their own readings.** A fire wider than a hexagon is two to ten calls from
      The Hub, one after the other — the head, the flanks, the ends — each answered from its own
      hexagon, new or cached (item 1). No batching; keep it simple.
- [x] **The cell shape and size are two constants at the top of the generator:** 15 km and six
      sides. The shape may be four or six sides (the two that tile without gaps).
- [x] **A toggle on the console map to show every hexagon or only the loaded ones**, so the
      tessellation and what we actually hold can both be seen (item 8).
- [x] **Hexagons with a Bureau station are always alive.** Their current values arrive every ten
      minutes for free, so they never need Open-Meteo for "now", only for the forecast; and they
      keep answering when Open-Meteo is offline (item 1).
- [x] **Bureau data for every state, not just South Australia**, polled politely: one file per
      state, downloaded only when it has changed (item 12).
- [x] **History is written only when an incident is present.** A Bureau station updating every ten
      minutes never writes history on its own; a snapshot is taken only when The Hub asks about a
      hexagon with an incident in it (item 2).
- [x] **Station rain and temperature feed the drought maths.** A compact six-hourly ledger per
      station is kept for that; the drought start-up fetches only the days the stations have not
      yet covered (item 7).
- [x] **One in-memory cache that holds everything we know about a hexagon** — the latest reading,
      recent history, the drought state, the elevation — rebuilt from the database
      when the service starts, so the database is not touched to answer a request (item 14).
- [x] **A map layer.** The hexagons we hold, as map shapes with one value each (temperature, fire
      danger, and so on), served by the API for any map to draw, The Hub's included (item 3).
- [x] **That map layer pre-built and cached.** Rendered once each time a hexagon refreshes, so a
      map that polls every minute costs nothing until something changes (item 14).
- [x] **Tables and clearer visuals on the console** so the data profile of every hexagon can be
      seen: a map with a switch for which value to colour by, a click that shows everything we hold
      for a point, per-hexagon tables (readings, history, drought, when it refreshes, how old), and
      charts of upstream spend (item 8).

### 1 · Fetch readings only where they are asked for, keyed on hexagons — no grid

The Hub (or any website) sends a point and gets the weather for it; that is the whole interface.
Inside, Australia is divided into hexagons of about 15 km and every reading belongs to one: a point
is answered by the reading held for its hexagon, fetched once at the hexagon's centre and shared by
everything inside it. The hexagons are not stored — the library works out any point's hexagon by
arithmetic — so a hexagon only exists once something inside it has been asked about, and empty
country costs nothing. The cells come from our own small generator — the shape (six sides) and the
size (15 km) are two constants at the top of it, and an upstream with a finer or coarser model can
be given its own size. A reading is kept until the upstream says it is stale, and when it is served
close to expiry it is refreshed in the background so the next ask is already fresh; The Hub sweeps
its open incidents every few minutes, so their hexagons stay warm by being asked about and go cold
on their own when the incident closes. A large fire is two to ten calls from The Hub, one after the
other — the head, the flanks, the ends — each answered from its own hexagon, new or cached. A place we
hold nothing for is fetched; there is no guessing from neighbours. A hexagon with a Bureau station in
it is different: its current values arrive every ten minutes for nothing (item 12), so it is always
alive — Open-Meteo is called for its forecast only, and only when the forecast is asked for — and it
keeps answering "now" when Open-Meteo is offline. The map layer shows which is which: a hexagon
with a station, a hexagon with a forecast loaded, both, and the wider drought areas (item 8). A pre-warmed grid is ruled out: the
free allowance (about 9,500 units a day at 5 or more
units a fetch) spread over the whole country is roughly 100 cells, far too coarse to be useful.
**Cost:** medium; no library needed; no scheduler beyond the daily drought step. **Removes:** the
"nearest reading within a radius" search, its time tiers, the governor, the terrain memo and the
coverage outlines.

### 2 · Keep history, and answer "what was the weather at this time"

Today a reading is a value that expires; nothing remembers what the weather *was*, so an incident
asked about late gets a reading taken later, carrying the time of the incident.
Each hexagon holds its current data — the hourly forecast for three days and the daily forecast for
seven — and, separately, its history: a snapshot of the *current conditions*, never the forecast,
taken only when The Hub asks about the hexagon and says an incident is present. A Bureau station
updating every ten minutes never writes history on its own; its latest values are simply kept. A
reading counts as current for three hours, so a hexagon asked about at 06:00 and again at 12:00 has
two entries, one asked about repeatedly inside three hours has one, and a small incident leaves
exactly one reading at its time. Asking the API for a past time returns the snapshot nearest that
time, with its own time, or says there is none. Only hexagons that had an incident have history. **Cost:** medium. One history table, created by a
migration script rather than the framework's automatic schema updates; nothing is ever deleted from
it. The Hub sends the incident's start time when it asks late.

### 3 · A proper version 1 of the API

Today the API is built by hand from maps of names to values, so there is no schema, no way to
generate a client, and no test that the field names are what The Hub expects. There is no
compression, no useful caching headers (the "has this changed" check never matches because every
response carries the time it was generated), three different error formats, and no way to ask for a
past time. Version 1 fixes all of that: typed responses with a generated OpenAPI document, one error
format, headers that let a client cache correctly, a time parameter, and the hexagon map layer from
item 0. One point per request, always: The Hub sends its calls one after the other. The old
`/api/weather` keeps answering in its old shape for one release. **Cost:** medium. No field The Hub
reads is renamed, and item 9 is the test that proves it. The Hub's client moves to the new paths
(about a day).

### 4 · Serve the official fire danger rating, and the modern fire behaviour index

The fire danger served today is the 1980 McArthur index on its pre-2022 bands, while the public have
been told an AFDRS rating (Moderate / High / Extreme / Catastrophic) since September 2022. The
official rating per district is fetched by The Hub and never seen here, and the grassland index is a
second copy of the same 1980 maths in The Hub. Put the official district rating, its index and the
total fire ban on every reading; add one endpoint that takes fuel inputs (curing, grass condition,
fuel load) and returns the McArthur indices and the CSIRO grassland index with its AFDRS rating,
from one set of formulas tested against the published worked examples; add the forest model later,
once the grassland one has been checked against a bad day's published number. **Cost:** medium for
grass and the rating; very large for forest. A wrong fuel model is a wrong number about a fire,
so every formula ships with its worked example and the official rating sits beside it. The Hub's
grassland maths moves here.

### 5 · Station data and warnings, not just forecasts

Everything served today is a model output. A crew standing in 60 km/h wind is told the forecast's
35, and nothing says a Fire Weather Warning is current. Read the Bureau's public station data
(every state, about 82 stations in South Australia alone, every ten minutes) and its warnings (every five minutes), and the CFS
total fire bans; put the nearest station's values on every reading beside the forecast values, with
the station's distance and time, and the current warnings for the point's district. Station data is
not an estimate, a prediction or a "reading" of a different grade — it is values, like the rest.
**Cost:** medium. The Bureau paused some open-data feeds in September 2026, so each must be
verified live and degrade gracefully; registering for the Bureau's free data licence is the clean
footing. There is no free lightning feed a service is allowed to use. *Built: the seven station files and the seven
warnings listings on `reg.bom.gov.au`, verified live 18 September 2026; a warning is joined to a hexagon
through its nearest station's public weather district.*

### 6 · Spend the upstream allowance by a plain budget, not a governor; Google Weather as the overflow

Today a "governor" (600 lines with its tests) widens the search radius when the upstream is
struggling; its own comments say it almost never moves, it forgets its position on restart, and it
ties *how much we spend* to *how accurate we are*, which are two different dials. The spend ledger
counts one unit per fetch while asking for thirty variables, so we do not actually know what a
fetch costs. Remove the governor — confirmed. In its place, per upstream: a budget counted from the
database against the measured cost of a fetch, a circuit breaker that stops calling after repeated
failures and tries again after the pause the upstream asked for, and a rate limit matching the
real per-minute limit. **Google Weather stays, as the overflow:** when Open-Meteo's allowance for
the day is used up, or Open-Meteo is not responding, the fetch goes to Google instead, on the same
hexagon, with the same shape of answer; Google's own spend is counted and capped the same way. When
both are unavailable, the last reading is served, with its time. **Cost:** small.

### 7 · Drought over a hexagon and its ring, stepped forward daily

The drought index needs a year of daily rain and temperature to start. Today the service fetches
that year for a 50 km cell on first use, then re-fetches it *every day* the cell is busy, and throws
the cell away and starts again when it goes quiet. Instead, drought is worked out over a wider area
than a reading: the hexagon the point is in plus the six around it (about 45 km across), because
drought is a property of a district, not a 15 km cell. Starting it needs a run of daily rain and
maximum temperature for the seven hexagons — and the Bureau's stations already give us both, every
day we have been running (rain since 9 am is the day's total just before 9 am; the day's maximum
comes from the samples). So the start-up fetches only the days we do not have: running for 30 days
and needing 100, it asks Open-Meteo's archive or Google for days 31 to 100 and nothing else. After
that it is free: each day the index is stepped forward from the station values and the readings
already held, and a quiet area keeps its state so it picks up where it left off. River discharge stays keyed on the river model's own smaller cells, because a river is a line.
**Cost:** medium. The daily step must run exactly once per area per day.

### 8 · A console that shows the data

The console is one page of tables; the map script is loaded by nothing; the only chart is a
four-pixel bar. Three pages: a **map** of the hexagons we hold, coloured by the value you choose
(temperature, humidity, wind, fire danger, official rating…), showing what each hexagon is — a
station in it, a forecast loaded, both, part of a drought area — with its age and whether it is
being kept fresh, with Bureau stations, warning areas, a time slider, and a
click that opens everything we hold for that point as tables — the reading, its history, the drought
state, when it next refreshes, how old it is; an **upstreams** page with spend per hour and per day
as bars against the allowance and the breaker's history; a **hexagons** page listing every one we
hold, sortable by state, age and next refresh. **Cost:** medium, and it needs items 1 and 3 first.
The map must never trigger a fetch — it draws the pre-built layer.

### 9 · A shared test that the API's shape has not changed

The only test of what the API looks like checks the keys of the status endpoint. The reading itself
— the thing The Hub depends on — is tested nowhere here and read by name over there, so a renamed
field breaks The Hub silently. Write the reading's shape down once as a JSON Schema, keep a copy in
both repositories, test here that what we serve matches it, test in The Hub that what it reads
matches it, and fail the build if the two copies drift. **Cost:** small — the cheapest item with the
best return, and what makes everything else safe to do.

### 10 · Fewer settings, none dead

Fifty-odd settings, of which three are read by nothing, thirteen tune the governor, seven tune the
radius search, and one switches on a term whose weight is zero. Keep one group of settings per
thing a deployment genuinely varies (how early to refresh ahead of expiry, which upstreams are on, which
sources are on, how long history is kept, the contact address, the console code) and make everything
else a constant beside the code that knows why; print every effective setting once at startup.
**Cost:** small, once items 1 and 6 have removed the machinery the settings tuned. The "assumed
drought factor" fallback goes: a reading with no drought data has no drought value.

### 11 · Security and operations

Every API key has every permission; the rate limit is invisible to the caller; the service reports
"ready" even when its database is dead; upstream spend is not exposed to monitoring; logs are plain
text; and every request writes a log row to the database before answering. Give keys scopes
(readings only, map layer only, and so on); return the standard rate-limit headers and a daily cap
per key; include the database in the readiness check; expose spend, breaker state and freshness as
metrics; write logs as structured JSON in production; batch the access-log writes; make the service
start in about two seconds rather than six; take a nightly backup of the history; note what a
second copy of the service would need (one refresh job at a time, a shared cache rebuild).
**Cost:** medium. Once the readiness check includes the database, a dead database shows as a
restarting container — which is what we want, but worth knowing in advance.

### 12 · The Bureau's station files, and how to handle them

The Bureau publishes its station data as files on a public server, refreshed every ten minutes: one
file per state listing every automatic weather station with its latest values (temperature, dew
point, humidity, wind speed and direction, gust, rain since 9 am, pressure) and the station's own
details (identifier, name, position, height). South Australia is about 82 stations in one file;
every state is read, seven files in all. Handling it: check each state's file every ten minutes but
download it only when the server says it has changed, which is what the Bureau asks and keeps the
traffic to a handful of downloads an hour; read every station out of it and keep the latest values
per station, exactly as published, plus a compact ledger of one row per station every six hours —
enough for the drought maths (item 7) and nothing like the incident history of item 2, which stations
never write; keep the station list
from the file itself (never a hand-typed list, so a new or moved station appears on its own); map a
point to its nearest station by distance; and, on every reading, carry that station's latest values
as the current conditions beside the forecast, with the distance and the time. A hexagon with a
station in it is alive for as long as the Bureau keeps publishing. The same server carries the warning products (item 5), read
the same way. Two practical points: the server wants a proper contact address in the request (the
service already has a setting for one, currently read by nothing), and the Bureau paused some feeds
during its platform upgrade in September 2026, so each file is checked live before it is relied on.
Registering for the Bureau's free data licence is the clean footing for serving its values on.
**Cost:** medium.

### 13 · Tidy the code so the other items have somewhere to land

Packages are grouped by kind of class rather than by feature; values are passed around as maps of
names rather than typed records; the empty and failure cases are handled by building strings rather
than by types the compiler checks; input is validated in three places; every class is prefixed
"Weather"; and there is dead code (unused helpers, two-thirds of the geometry class, a
conditional-request feature nothing calls). Group by feature (hexagons, upstreams, storage, science,
API, console, platform), use typed records with one translation layer at the API edge, validate
input once at the boundary, drop the prefix, delete the dead code, and add two test layers above
the unit tests: a schema check and one real end-to-end test against a temporary database. **Cost:**
medium, mostly as a by-product of items 1 to 3.

### 14 · Caching and speed

The in-memory cache from item 0: everything we hold about a hexagon in one structure, replaced whole
when it refreshes and rebuilt from the database at startup, so answering a request is one hexagon
lookup and one map read with no database involved. The map layer rendered once per refresh per
value into bytes with a fingerprint, so a polling map gets "unchanged" until something changes. With
the generated-at time out of the response body, the fingerprint finally works. Set targets (a point
read in under 5 ms, the map layer in under 50 ms, well under the daily allowance) and measure them
rather than hope. **Cost:** medium, mostly a consequence of items 1 to 3.

### 15 · What gets deleted

Roughly 3,000 lines go and 1,500 come back, mostly typed records and SQL: the governor and its
tuning and tests, the spend ledger's in-memory rebuild, the per-host rate limiters,
the radius search and time tiers in the cache, the coverage outlines, the terrain memo, two-thirds of
the geometry helpers, the hand-built JSON assembly, the dormant map script, and the unread settings.
The maths, the security, the diagnostics and the console chrome stay. **Cost:** small each, but
nothing goes before the item that replaces it has landed.

### 16 · The full fire picture on every active hexagon

An *active* hexagon is one The Hub has asked about — not one that merely has a station in it. Every
active hexagon carries the whole set, computed from its reading and refreshed with it: the McArthur
forest index (FFDI) and grassland index (GFDI), the modern fire behaviour index and its rating
(item 4), the official district rating and total fire ban, the wind — speed, direction, gusts and
the next wind change with its time — the current conditions (station values where there is a
station, item 12), the drought index for its area (item 7), the warnings covering it (item 5), and the land use of
the hexagon and the point (item 19), which decides whether the forest or the grassland index leads.
None of it is worked out on request: it is on the hexagon, so The Hub's ask is a lookup and the
map layer can colour by any of it. A station-only hexagon carries station values and nothing
computed until The Hub asks. **Cost:** medium, once items 4, 5, 7 and 12 exist; this is the item
that ties them together and settles what "active" means.

### 17 · Elevation built in, from a terrain file

Elevation is needed for the reading (a hexagon on a ridge is not a hexagon in a valley) and for the
forest fire model later (slope). Today it is fetched point by point from an upstream and remembered
in a cache that is lost on restart. Instead, keep a terrain file on the service's own volume — a
GeoTIFF such as Geoscience Australia's 9-second digital elevation model, about 250 m per cell and a
few hundred megabytes for the whole country, which is far more than accurate enough — and read the
elevation of each hexagon's centre from it once, when the hexagon is first created, storing it with
the hexagon. Mean slope per hexagon can come from the same file when the forest model wants it. No
upstream call, no expiry, works offline; a finer file can replace it later without changing anything
else. **Cost:** small to medium. The file is downloaded once and mounted, not committed.

### 18 · Grass curing

The grassland indices need curing — how dry the grass is, as a percentage — and no open API
publishes it for South Australia; The Hub today has an operator type it in per fire-ban district
from the CFS's weekly map. Gully should hold it instead, since it is the one computing the grass
indices: first, look for it on the CFS GeoHub (the same public map server the fire-ban districts
and ratings come from) and read it weekly if a curing layer is published; otherwise, a console page
where it is entered per district each week in fire season, with the date it was entered. Either
way it is carried on every active hexagon as a value, used by item 16's grass indices, shown on the
map, and a hexagon with no curing figure has no grassland index. **Cost:** small for the console
entry; small to medium for the GeoHub read if the layer exists.

### 19 · Land use per hexagon, and at the point

What a place *is* — forest, grassland, cropland, scrub, built-up, water, bare — matters twice: it is
worth returning for the point itself ("this incident is in pine forest", "this is a car park"), and
it decides which fire index is the right one. Keep a national land-cover file on the service's own
volume, the same way as the terrain file (item 17): Geoscience Australia's land cover or ABARES'
catchment-scale land use, both free, both fine at 25–50 m. When a hexagon is first created, overlay
it on the file and count the cells: the result is a percentage per class stored with the hexagon
(say 62% grassland, 30% forest, 5% built-up, 3% water), plus the class at the exact point asked
about. Item 16 then uses it: a hexagon that is mostly forest leads with FFDI, mostly grass leads
with GFDI, and one that is mostly water or built-up carries the indices but says they apply to
little of it. Both the percentages and the point's class go on the reading and onto the map layer,
so a hexagon can be coloured by what it is as well as by its weather. **Cost:** medium. The file is
downloaded once and mounted, not committed; the overlay is done once per hexagon, never on request.

---

**If only three:** item 0 (a day, and every line stands alone), item 9 (a day, and it makes
everything else safe), item 1 (the design that scales to Australia and gives large fires their own
reading). *All twenty were done.*

---

← [05 · Decisions](05-decisions.md) · [Docs index](README.md)
