# Anvil — the questions that decide it

The weather service, split out of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database) and rebuilt.

**Round 2 changed almost everything.** Round 1 decided a lift-and-shift of working Java; round 2
reverses it. Anvil is now a **greenfield Python service in Docker**, built to a product definition
rather than ported from one, with a pluggable model underneath it and a handful of endpoints on top.

Round 1's decisions that survive are marked. Round 2's are new. **Section A is five questions and
they block the architecture, not the approval** — the approval happened.

---

## The name

> *"When I say weather manager, are we talking about the Hub or the weather model API?"*

**The service is Anvil.** The Hub's `WeatherManager` keeps its name, because it is a Hub manager
alongside `IncidentManager` and `MetricsManager` and it does a Hub job — deciding *when* an incident
is worth asking about. It just asks Anvil now.

So the sentence disambiguates itself: **"the weather manager"** is always the Hub's, and **"Anvil"**
is always the service. Nothing is called "the weather service" or "the weather model" again.

**Why Anvil.** The anvil is the flat top of a cumulonimbus — the visible sign that the atmosphere has
become dangerous, and the shape a fire makes when it grows big enough to build its own weather. It is
also the thing you hammer on: solid, fixed, the piece that does not move while everything else is
worked against it. Two syllables, no ambiguity, and it reads well everywhere it has to appear —
`AnvilClient`, `ANVIL_API_KEY`, `anvil.surefirehudson.com`, *"Anvil says the FFDI is 32."*

Alternates are [Q1](#q1) if it does not land.

---

## What Anvil is

**One point, one answer: now and the next seventy-two hours.**

| Block         | Contains                                                                              |
|---------------|-----------------------------------------------------------------------------------------|
| **Weather**   | Temperature, humidity, wind speed and direction, rain, pressure, cloud — now, hourly to 72 h, and daily |
| **Drought**   | The soil moisture deficit and the drought factor, integrated from a year of history rather than assumed |
| **Flood**     | What has already fallen, what is still coming, how saturated the ground is, what the river is doing |
| **Fire**      | FFDI and GFDI, now and across the forecast, with rate and direction of spread          |

Everything else is machinery in service of that: the sources that feed it, the cache that stops it
costing anything, and the provenance that says how good the answer actually is.

**Two independent knobs, by design.** What is *in the model* and what is *on the API* are separate
choices — a source can be added without changing the contract, and a field can be published or
withheld without touching the model. That is [Section B](#section-b--the-model) and
[Section C](#section-c--the-api), and the mechanism is [A3](#a3).

---

## How to answer

| Write     | Means                                                                  |
|-----------|------------------------------------------------------------------------|
| `default` | Accept the stated default — it becomes the decision                    |
| `skip`    | Stop asking, keep the assumption, accept it is unexamined              |
| `?`       | Expand the recommendation before deciding                              |
| `unsure`  | Park it, keep the default, but it stays open                           |

Answers get applied here and logged at the bottom; nothing lives only in chat.

---

## Decisions

James, 15 September 2026, rounds 1 and 2.

### Round 2 — the rebuild

| #                           | Decision                                                                                                                                                   |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <a id="w-015"></a>**W-015** | **Python, in Docker.** A greenfield build to the product definition above, not a port of the Java. **This reverses [W-002](#w-002)** |
| <a id="w-016"></a>**W-016** | **The service is Anvil.** The Hub's `WeatherManager` keeps its name and its job                                                                             |
| <a id="w-017"></a>**W-017** | **The product is now + 72 hours at a point**: weather, drought factor, flood and rain, and the fire indices                                                 |
| <a id="w-018"></a>**W-018** | **The model is pluggable in two independent directions** — sources in and out of the model, fields in and out of the API                                     |
| <a id="w-019"></a>**W-019** | **A handful of endpoints**, not a large surface                                                                                                             |
| <a id="w-020"></a>**W-020** | **A Cloudflare tunnel** fronts it. Nothing inbound on the host                                                                                              |
| <a id="w-021"></a>**W-021** | **One shared key**, in both `.env` files: `ANVIL_API_KEY`                                                                                                   |
| <a id="w-022"></a>**W-022** | **The Hub is repointed** at the tunnel path                                                                                                                 |
| <a id="w-023"></a>**W-023** | **Curing comes off satellite greenness**, against the published Australian products, rather than off an operator's keyboard. Manual entry becomes an override, not the source |
| <a id="w-024"></a>**W-024** | **A comprehensive model is the goal**, added to and taken from over time — not a fixed set of four providers                                                 |

### Round 1 — what survives the rebuild

| #                           | Decision                                                                                                          | Status                    |
|-----------------------------|---------------------------------------------------------------------------------------------------------------------|---------------------------|
| <a id="w-001"></a>**W-001** | The justification is the **development context budget**, not production risk                                       | **Holds.** Reinforced — a Python build is smaller again |
| <a id="w-002"></a>**W-002** | Version one is a lift-and-shift                                                                                    | **Reversed by [W-015](#w-015)** |
| <a id="w-003"></a>**W-003** | The Hub keeps **one weather door** — `WeatherManager`, a client with a cache                                       | **Holds**                 |
| <a id="w-004"></a>**W-004** | **Anvil sets the cache expiry**; the Hub obeys it                                                                  | **Holds**                 |
| <a id="w-005"></a>**W-005** | Service, maths and types all leave the Hub                                                                         | **Holds, and goes further** — there is no shared Java left to leave |
| <a id="w-006"></a>**W-006** | **GFDI is Anvil's**, not `MetricsManager`'s. D-187 reversed                                                        | **Holds**                 |
| <a id="w-007"></a>**W-007** | Fuel layers, fuel load and curing follow it                                                                        | **Holds**, and [W-023](#w-023) changes where curing comes from |
| <a id="w-008"></a>**W-008** | Anvil owns **its own Postgres**                                                                                    | **Holds.** Migration is moot under [W-015](#w-015) — see [F1](#f1) |
| <a id="w-009"></a>**W-009** | Degradation is **cache, nearest cached, nothing**. No ceiling, no extrapolation                                    | **Holds**                 |
| <a id="w-010"></a>**W-010** | Switch and delete, no flag                                                                                         | **Holds**, gated on Anvil answering first |
| <a id="w-011"></a>**W-011** | Bureau permission exists; station data is not in the first cut                                                     | **Holds**                 |
| <a id="w-012"></a>**W-012** | MET Norway, the archive, verification and tuning are later                                                         | **Softened** — [W-024](#w-024) makes them planned rather than deferred |
| <a id="w-013"></a>**W-013** | `/api/weather` **stays a Hub endpoint**. Consumers see no change                                                   | **Holds** — and matters more now that Anvil has its own hostname |
| <a id="w-014"></a>**W-014** | The databases are disposable **at James's discretion and by nobody else**                                          | **Holds**                 |

---

## The split

| Anvil owns                                                                    | The Hub owns                                                        |
|-------------------------------------------------------------------------------|-----------------------------------------------------------------------|
| Every upstream call, and the only keys that make them                        | Nothing upstream. It calls one host: `anvil`                          |
| The model: sources, derivations, and the graph between them                  | —                                                                      |
| The cache, its reach, its expiry and the governor                            | A cache whose expiry Anvil dictates ([W-004](#w-004))                 |
| Its own Postgres and every table in it                                       | None of them                                                          |
| Drought, KBDI, FFDI, **GFDI**, spread, flood                                 | —                                                                      |
| Fuel class, fuel load, and **curing off satellite** ([W-023](#w-023))        | —                                                                      |
| Its own terrain, for its own three-dimensional reach                         | Terrain for everything else it already uses it for                    |
| The allowance ledger and the spend figures                                   | `/console/usage`, reading them over the wire                          |
| Its own manager page — Anvil's data, not the Hub's                           | The Hub console, unchanged                                            |
| — | `WeatherManager`: when to ask, the cache, the sweep, the stagger      |
| — | The `weather`, `forecast` and `metrics` components on an incident     |
| — | `/api/weather` and every consumer contract ([W-013](#w-013))          |
| — | `fire_danger_day` — the published CFS ratings. A ledger, not weather  |
| — | Incident data. **None of it crosses**                                 |

---

## Proposed: the API

*A concrete proposal to react to rather than an open question. Five endpoints, all `GET` but one.*

| Endpoint                          | Serves                                                                                                     |
|-----------------------------------|--------------------------------------------------------------------------------------------------------------|
| `GET /v1/at?lat=&lon=`            | **The answer.** Now and 72 hours: weather, drought, flood, fire, and the provenance block                    |
| `GET /v1/coverage.geojson`        | What the cache holds, as a map layer. Serves only what is already held, so a display can poll it for nothing |
| `GET /v1/vocabulary`              | The band tables — FFDI, GFDI, KBDI, wind, humidity. **This is how [A9 from round 1](#c4) gets answered**     |
| `GET /v1/health`                  | Every source: enabled, last success, allowance remaining, licence, and what it contributes                   |
| `POST /v1/overrides/curing`       | The one write. A human correcting what the satellite said, attributed and dated                              |

Everything is behind `ANVIL_API_KEY`. The `/v1/` prefix is the versioning story — additive changes in
place, a new prefix if a field ever has to change meaning.

## Proposed: the model

*Also a proposal. This is the mechanism [W-018](#w-018) asks for.*

Three layers, and the seam between them is what makes things pluggable:

| Layer           | Is                                                                                                     | Adding one means                            |
|-----------------|----------------------------------------------------------------------------------------------------------|---------------------------------------------|
| **Sources**     | Something that fetches. Declares what **variables** it can supply, over what horizon, at what cost, under what licence | Write a class, register it. Nothing else changes |
| **Derivations** | Something that computes. Declares its input variables and its output variables — KBDI, drought factor, FFDI, GFDI, spread, flood | Write a function, declare its inputs         |
| **Publication** | What goes on the wire. Maps internal variables to API fields, per block                                 | A line in a manifest                        |

A request resolves a graph: the publication layer asks for fields, the fields name variables,
variables are satisfied by derivations or by sources, and anything unsatisfiable **comes back absent
with a reason** — never estimated, never defaulted silently. That is the same rule as
[W-009](#w-009), applied one level down.

**So the two knobs are real.** Disable a source and the graph reroutes or the field goes absent with
a reason. Withhold a field and the model does not notice.

---

## Status

| Section                             | Questions | Blocking |
|-------------------------------------|-----------|----------|
| **A — Blocking**                    | **5**     | **5**    |
| B — The model                       | 6         | —        |
| C — The API                         | 6         | —        |
| D — Curing, fuel and the satellites | 6         | —        |
| E — The Hub side                    | 6         | —        |
| F — Storage and the cache           | 5         | —        |
| G — The manager page                | 4         | —        |
| H — Deployment, keys and the tunnel | 5         | —        |
| I — Correctness                     | 4         | —        |
| J — Later                           | 4         | —        |
| [Q1 — the name](#q1)                | 1         | —        |
| **Total**                           | **52**    | **5**    |

---

# Section A — Blocking

*Five. Each one decides an architecture rather than a detail.*

---

<a id="a1"></a>**A1. When two sources can answer the same question, does one win or do they blend?**

This is the central question of a "comprehensive model" and the Hub answered it the other way, with a
good argument:

> *"Every provider here returns **a model's estimate for a grid cell**, not a station reading, and
> averaging two global models is not fusion, it is a third model nobody validated. One provider
> answers; which one answered travels with the answer."*

That argument is sound **for models**. It stops being sound the moment real observations enter —
combining an observation with a model estimate is not averaging two guesses, it is the thing weather
services actually do. And [W-011](#w-011) says the Bureau permission exists, so observations are
coming.

|   | Policy                                                                                     | Consequence                                                                     |
|---|----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| 1 | **One source wins per variable**, by configured priority. Which one answered travels with the answer | The Hub's rule, kept. Simple, explicable, and it wastes the observations when they arrive |
| 2 | **Observations correct models; models never correct each other.** A station within *n* km and *m* minutes nudges the model estimate; two models never blend | Honest about the difference in kind. Needs a correction rule and a distance/time window |
| 3 | **General blending** — weighted by declared per-source error                                | The most "model"-like, and it needs an error model per source per variable that nobody has |

*Why it blocks:* it decides whether a variable has one provenance or several, which decides the
answer's shape, which decides [A2](#a2) and the API contract. It is very hard to retrofit.

*Default:* **option 2, built as option 1 first.** Ship one-source-wins now, with the resolver designed
so a correction step can be inserted per variable later without changing the answer's shape — which
means the provenance block has to be per-variable from day one, not per-answer.

**A:**

---

<a id="a2"></a>**A2. What does provenance look like when the answer is assembled from many sources?**

Carried from round 1, and harder now. Today one line of English carries it: *"cache: anchor 15.0 km
away, 4 min old"*. Under [W-024](#w-024) an answer might have temperature from ACCESS-G, rain from
Open-Meteo, curing from a satellite pass three days ago and a drought factor integrated over a year —
four ages, four distances, four licences, in one payload.

And [W-009](#w-009) removed the time ceiling, so **the label is the only thing** standing between a
consumer and an arbitrarily old reading.

| Level          | Carries                                                                     |
|----------------|--------------------------------------------------------------------------------|
| **Per answer** | `decision` in English, the worst age in it, and whether any rung below the first was used |
| **Per block**  | `observedAge`, `heldFor`, `offsetMetres`, `degraded`, and the source that answered |
| **Per variable** | The source and its age — needed if [A1](#a1) lands on option 2 or 3           |

*Why it blocks:* [13 · Concerns](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/13-concerns.md)
calls this the failure the system cares about most, and the consumer contracts are being written
against this shape right now. A field added in six months is a field everyone has learnt to ignore.

*Default:* **all three levels, from the first version.** Per-variable provenance is cheap to carry and
impossible to add later, and it is what makes [A1](#a1) option 2 reachable without a rewrite.

**A:**

---

<a id="a3"></a>**A3. Is field selection a server decision or a caller decision?**

[W-018](#w-018) says data can be added to and removed from the API. Two very different mechanisms:

|   | Mechanism                                                                     | Costs                                                                        |
|---|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| 1 | **Server-side manifest.** Anvil publishes what it is configured to publish; every caller gets the same shape | One contract, one thing to test, and a change is a deploy. Callers cannot trim a payload they do not want |
| 2 | **Request-side selection** — `?blocks=weather,fire` or `?fields=`               | Callers take what they need; the Hub can skip flood on a medical callout. Every combination is now a shape somebody depends on |
| 3 | **Both** — a manifest bounds what exists, a request narrows within it           | Most flexible, most to test, and the cache key now includes the selection      |

*Why it blocks:* it changes the cache key, the contract test, and whether "add a field" is a deploy or
a conversation.

*Default:* **option 1 with block-level narrowing** — the manifest decides what exists, and a caller
may ask for whole blocks (`weather`, `drought`, `flood`, `fire`) but not individual fields. Blocks are
few, cacheable and already how the answer is shaped; per-field selection is a combinatorial contract
nobody needs yet.

**A:**

---

<a id="a4"></a>**A4. Which sources are in the first cut?**

[W-024](#w-024) makes the source list a living thing, which means the first cut is a choice rather
than a definition. Everything named so far, with what it costs:

| Source                                   | Gives                                          | In v1?                                            |
|------------------------------------------|------------------------------------------------|---------------------------------------------------|
| **Open-Meteo** `/v1/forecast`            | The widest variable set, blended best-available | **Yes.** The backbone                             |
| **Open-Meteo** `/v1/archive`             | A year of daily history for the KBDI spin-up   | **Yes.** Drought does not exist without it        |
| **Open-Meteo** `/v1/flood` (GloFAS)      | River discharge now and forecast                | **Yes** — cheap, one call per cell per day        |
| **Open-Meteo** `/v1/bom` (ACCESS-G)      | The Australian national model                   | Yes, but off by default — it answered all-null in September |
| **Google** Maps Platform Weather         | Rich current conditions. The only one that bills | Yes, last in order                                |
| **Height map** (TIFF?)                   | True ground height, for the 3-D reach          | **[B1](#b1)** — what it is and where from is not settled |
| **CFS fuel-type layers**                 | Fuel class at a point, for GFDI                 | **Yes** — GFDI is Anvil's now ([W-006](#w-006))   |
| **Satellite greenness** (MODIS/Sentinel) | Curing, without a keyboard                      | **[Section D](#section-d--curing-fuel-and-the-satellites)** — the headline new thing, and the least certain |
| **MET Norway** Locationforecast          | A free provider that permits commercial use     | Later ([W-012](#w-012)) — unless [D6](#d6) pulls it forward |
| **Bureau station observations**           | Real point readings                             | Later ([W-011](#w-011)) — and the reason [A1](#a1) matters |

*Why it blocks:* it is the scope of the build, and two rows are question marks rather than entries.

*Default:* everything marked **Yes** above, satellite curing as a **stretch goal in v1** with the
manual override as the guaranteed path, and the height map settled in [B1](#b1).

**A:**

---

<a id="a5"></a>**A5. Is there a fetchable, licensed curing product — and what happens if there is not?**

[W-023](#w-023) is the best idea in the whole plan and the least verified. What is known: curing maps
are made from satellite greenness with ground truthing, the CFS publishes a weekly map for South
Australia, and there are Australian products in this space — Bureau and CFA curing guidance, and the
ANU flammability monitoring work. **What is not known is whether any of them is machine-readable, at
what resolution, under what licence, and how quickly.**

Three routes, in descending order of how much I would trust them:

|   | Route                                                                              | Risk                                                                    |
|---|----------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| 1 | **Consume a published curing product** as data                                     | Best if it exists and is licensed. Needs finding and confirming first        |
| 2 | **Derive curing from raw greenness** — MODIS or Sentinel NDVI against a seasonal baseline | Always available. It is our number, not the CFS's, and it will disagree with theirs sometimes |
| 3 | **Keep the manual register** as the source                                          | What exists today. Blocks GFDI wherever nobody has typed a figure           |

*Why it blocks:* GFDI is Anvil's now ([W-006](#w-006)), and a district with no curing figure has no
grassland index. If route 1 does not exist and route 2 is a month of work, the honest v1 is route 3
with the others behind it.

*Default:* **spend a day establishing whether route 1 exists before committing to anything.** Build
route 3 regardless — it is a table and a form, it is the override in
[the API](#proposed-the-api) anyway, and it means GFDI is never blocked on a satellite. Then route 1
if it is real, route 2 if it is not.

**A:**

---

# Section B — The model

<a id="b1"></a>**B1. What is the height map, exactly?** The Hub samples terrain from file-backed tiles
on disk to make the reach three-dimensional; the proposal names "height map TIFF?" with a question
mark. Anvil needs the same thing and should not depend on the Hub's disk. *Default:* Geoscience
Australia's 1-second SRTM-derived DEM as GeoTIFF, clipped to the state, baked into the image or
mounted as a volume — static data, no fetch, no failure mode.
**A:**

**B2. Is the source contract a fetch, or a fetch plus a cost declaration?** The Hub's providers
declare call weight because Open-Meteo counts variables × span, and Google bills per endpoint.
*Default:* a source declares variables, horizon, **call weight**, licence and commercial-use flag.
The governor and the attribution both read it, so neither is folklore.
**A:**

**B3. Does the governor survive?** The Hub's governor moves reuse windows off allowance pressure, with
a dead band and asymmetric hysteresis. It is insurance that rests at its floor essentially always.
*Default:* yes, ported in concept — it is a hundred lines in Python and it earns its keep on the one
day Open-Meteo is down.
**A:**

**B4. Does a derivation ever run without all its inputs?** The Hub has a
`fallbackDroughtFactor: 8` for when the spin-up cannot complete. *Default:* **no fallbacks that
produce a number.** A missing input means the derived value is absent with a reason. A confident
figure resting on an assumed input is exactly the failure [W-009](#w-009) forbids one level up.
**A:**

**B5. Is `xclim` a dependency or a reference?** The Hub hand-ported KBDI and the drought factor and
caught two bugs by checking against xclim — `41x² + x` not `41x² + 1`, and Finkele's ceiling.
*Default:* **a dependency where it covers the quantity**, our own code only where it does not. Not
hand-porting equations is most of the reason to be in Python.
**A:**

**B6. How are the fire indices themselves implemented?** FFDI is McArthur Mk5 in Noble, Bary and
Gill's (1980) form; GFDI is the same paper. *Default:* our own code, from the published equations,
verified against the Java tests' hand-worked values — see [I1](#i1).
**A:**

---

# Section C — The API

**C1. Is `/v1/at` the whole answer, or is the forecast a second call?** The Hub splits `weather` and
`forecast` components and `?forecast=true` on the endpoint. *Default:* **one call, everything.** 72
hours is not a large payload, and [W-017](#w-017) makes now-plus-72 the product rather than an option.
**A:**

**C2. Hourly to 72 hours, or hourly to 48 and daily beyond?** The Hub carries 72 forecast hours and
cuts the wind-change search at 48. *Default:* **hourly for all 72**, plus daily for the same span.
**A:**

**C3. Does `/v1/at` accept anything but a point?** A bounding box would serve a map without *n* calls.
*Default:* **a point only** in v1. `coverage.geojson` already answers "what do you know about this
area", for nothing.
**A:**

<a id="c4"></a>**C4. Does `/v1/vocabulary` settle the band tables?** Round 1 left this blocking: the
Hub publishes `FireDanger.BANDS`, `Kbdi.BANDS` and the rest through `Vocabulary` so no consumer
computes them, and [W-005](#w-005) sends them out of the Hub. *Default:* **yes.** Anvil is the source
of truth, the Hub fetches at startup, caches to disk, and serves the last good copy with its age when
Anvil is down. One place where Catastrophic-starts-at-100 is written.
**A:**

**C5. What does `/v1/health` show, and can it be public?** *Default:* every source with its state,
last success, allowance and licence — and it stays behind the key, because allowance figures are
operational detail.
**A:**

**C6. How do the licences travel?** Open-Meteo is CC BY 4.0; attribution has to survive two hops to a
consumer's screen. *Default:* every answer carries the attribution strings for the sources that
actually contributed to it, and `/v1/health` carries the full list.
**A:**

---

# Section D — Curing, fuel and the satellites

**D1. Which satellite, at what cadence?** MODIS is daily at 250–500 m and ageing; Sentinel-2 is 10–20 m
every five days and much heavier to process. *Default:* **MODIS-class daily at 500 m** — curing is a
district-scale quantity and the Hub's own fire-ban districts are enormous. Sentinel is a later
refinement, not a starting requirement.
**A:**

**D2. Where does the imagery come from?** Bulk NASA/Copernicus downloads, or an analysis-ready service
that returns an index at a point. *Default:* **a point/small-area query service** if one is available
under licence — Anvil should not become a raster pipeline in its first month.
**A:**

**D3. How is derived curing validated?** If [A5](#a5) lands on route 2 we are publishing our own
number where the CFS publishes theirs. *Default:* the manual register doubles as ground truth — when
an operator enters the CFS figure, Anvil records the disagreement, and the manager page shows the
error over time. That is the verification loop arriving early and for free.
**A:**

**D4. Does fuel load stay a table?** McArthur's 4.5 t/ha seeds the register today. *Default:* **yes, a
table by fuel class**, overridable. Deriving fuel load is [J3](#j3).
**A:**

<a id="d5"></a>**D5. Who may write the curing override, and what if the write fails?** Carried from
round 1. Smaller now — [W-023](#w-023) makes the override a correction rather than the only path — but
a correction that silently failed still looks like a district nobody has updated. *Default:* the
Hub's key writes, the operator's name travels in the payload, a failure is an error on the screen and
nothing is queued or shadowed. Observer, date and source are stored with the row.
**A:**

<a id="d6"></a>**D6. Does the CFS fuel-type layer move cleanly?** GFDI needs fuel class at a point, off
layers the Hub reads today. *Default:* the layers move to Anvil as files, the same way the height map
does.
**A:**

---

# Section E — The Hub side

**E1. What replaces `WeatherService` in the Hub?** *Default:* an `AnvilClient` — HTTP, key, timeout,
retries off — behind `WeatherManager`, which keeps deciding when to ask. Nothing else in the Hub
knows Anvil exists.
**A:**

**E2. Is the call on the incident path synchronous?** Weather attaches when an incident is raised, and
that now crosses a network. *Default:* **asynchronous** — the incident is created without weather and
the component attaches when the answer arrives. Two-second timeout, no retry in the request.
**A:**

**E3. What happens to `MetricsManager`?** With GFDI, spread, the registers and the fuel layers gone
([W-006](#w-006), [W-007](#w-007)) it is a manager that copies a block onto an incident. *Default:*
**deleted.** `WeatherManager` fills the `metrics` component from Anvil's answer; the enrichment-matrix
row and the consumer contract are untouched.
**A:**

**E4. Does `IncidentWatchStatistics` still ask per agency centroid?** *Default:* unchanged behaviour,
but **cache-only** — a statistics build may read what the Hub holds and must never trigger a fetch.
**A:**

**E5. Does the Hub start when Anvil is down?** Startup is a gated phase sequence, and under
[C4](#c4) it may also want the band tables. *Default:* yes — every Anvil startup step is best-effort
and none of them gates readiness.
**A:**

**E6. Does `/console/weather` survive, and what does it show?** *Default:* it stays and becomes a
thinner thing — what the Hub has cached, how old, and Anvil's reachability. Anvil's own state lives on
Anvil's page ([Section G](#section-g--the-manager-page)).
**A:**

---

# Section F — Storage and the cache

<a id="f1"></a>**F1. Cold start or migration?** [W-008](#w-008) said migrate. Under [W-015](#w-015)
there is no shared schema to migrate into, and [W-014](#w-014) plus *"we would only be losing three
weeks"* makes the loss trivial. *Default:* **cold start.** The drought spin-up re-runs per cell on
demand — it is the expensive path, but it is bounded by activity and it buys a clean schema.
**A:**

**F2. What is the unit of the cache?** The Hub uses a proximity anchor with a three-dimensional reach.
A greenfield alternative is to key on the provider's own grid cell, which is what the reach
approximates. *Default:* **keep the anchor.** The grid cell is per-provider and unknowable for some;
the anchor is one concept that works across all of them, and it is the reason twenty appliances on one
fire ground cost one call.
**A:**

**F3. Postgres and PostGIS?** *Default:* **yes**, same as the Hub — proximity queries want it, and it
is one less thing that is different.
**A:**

**F4. What is backed up?** [W-014](#w-014) says the data is disposable. *Default:* nothing, **except
the curing overrides** — every other row is re-derivable and that one is a human's work.
**A:**

**F5. What does a restart cost?** *Default:* nothing. Anchors, cells and the ledger persist and
rehydrate; verify it rather than assume it.
**A:**

---

# Section G — The manager page

*"Nothing fancy but certainly comprehensive."*

**G1. What is on it?** *Default:* five things — **sources** (state, last success, allowance, licence);
**the map** (anchors and cells with their reach, ages and which rung they last answered on);
**a point probe** (type a coordinate, see the full answer with its provenance); **curing** (the
derived figure per district, the override form, and the disagreement history from [D3](#d3)); and
**the ledger** (calls and spend by source by day).
**A:**

**G2. Server-rendered or a single-page app?** *Default:* **server-rendered**, FastAPI with Jinja
templates and a Leaflet map. Comprehensive does not mean interactive, and a build step is a second
thing to keep working.
**A:**

**G3. How is it authenticated?** It is on a public hostname behind the tunnel. *Default:* Cloudflare
Access in front of the page routes, with the API routes on the key. Two mechanisms, each doing what it
is good at.
**A:**

**G4. Does it show the rung distribution?** [W-009](#w-009)'s three rungs and no ceiling mean
"everything is coming off the nearest-cached rung" should be visible before somebody notices the
numbers look odd. *Default:* yes — answers by rung over the last hour, on the front page.
**A:**

---

# Section H — Deployment, keys and the tunnel

**H1. Is the hostname `weather.` or `anvil.`?** [W-020](#w-020) proposes
`weather.surefirehudson.com`. *Default:* **`anvil.surefirehudson.com`**, with `weather.` as a CNAME if
it is already wired. The whole point of the name is that "weather" is the ambiguous word.
**A:**

**H2. What is in the compose file?** *Default:* four services, mirroring the Hub's shape — `app`,
`db`, `cloudflared`, and a `worker` for the scheduled jobs (spin-ups, satellite passes, sweeps).
**A:**

**H3. One key or two?** [W-021](#w-021) says one shared `ANVIL_API_KEY` in both `.env` files.
*Default:* **one, and it is the Hub's** — Anvil issues keys the way the Hub does, and the Hub holds
one. A second consumer gets its own rather than sharing.
**A:**

**H4. VPS or Pi?** Carried from round 1 and now easier: [W-023](#w-023)'s satellite work and the
archive are real load. *Default:* **a small VPS.** A Pi on a domestic connection has no out-of-band
access on the day it matters.
**A:**

**H5. Does the repository get renamed?** It is `Weather-Modelling` today. *Default:* rename to
`anvil` once [Q1](#q1) confirms the name.
**A:**

---

# Section I — Correctness

<a id="i1"></a>**I1. How do we know the Python arithmetic matches the Java?** The Java carries an FFDI
value worked by hand from the published equation, KBDI checked against xclim, and 1,511 lines of
tests. A rewrite is exactly where those drift silently. *Default:* **port the expected values first,
as language-neutral JSON fixtures** — inputs and known-correct outputs — and make them a test suite in
Anvil before any of the maths is written. A day's work, and it is the difference between a rewrite
that is safe and one that is brave.
**A:**

**I2. Is there a contract test between the Hub and Anvil?** *Default:* a golden payload, checked into
both repositories, asserted by both sides in CI.
**A:**

**I3. In what order do the two repositories change?** A switch with no flag ([W-010](#w-010)) means
one sequence works. *Default:* Anvil stands up and answers first; then the Hub's swap and deletion
land as **one commit**.
**A:**

**I4. What does "Anvil answers" mean, as a gate?** *Default:* `/v1/at` returns all four blocks for
three known points with correct provenance, `/v1/vocabulary` matches the Java band tables exactly, and
the [I1](#i1) fixtures pass. Then the Hub switches.
**A:**

---

# Section J — Later

*Named so they are designed for, not built.*

**J1. Bureau station observations.** Permission exists ([W-011](#w-011)). The reason [A1](#a1) matters,
and the truth that makes verification mean something.
**A:**

**J2. The historical archive and hindcast scoring.** What Anvil got right, per source, at 6, 24 and 72
hours, against persistence. [D3](#d3) is this loop arriving early for curing.
**A:**

<a id="j3"></a>**J3. Fuel load from imagery.** A photograph in, tonnes per hectare out. Realistic in
Python and **the dataset is the project** — labelled photographs with measured loads, which nobody has
yet. *Default:* start collecting the labels now, from every fuel-load figure an operator enters
alongside a photograph, and the model becomes possible in a year rather than never.
**A:**

**J4. MET Norway, and the commercial licence question.** Deferred ([W-012](#w-012)) on the condition
Anvil stays internal-only. PropertyWatch is the trigger to revisit.
**A:**

---

<a id="q1"></a>## Q1 — the name

**Anvil** is the recommendation and it is used throughout this document. If it does not land:

| Name         | For                                                                                     | Against                                     |
|--------------|-------------------------------------------------------------------------------------------|---------------------------------------------|
| **Anvil**    | The cumulonimbus top; the shape a fire makes when it builds its own weather. Solid, hammered on. Unmistakably not "the Hub" | Does not say *weather* to a non-meteorologist |
| **Beaufort** | A wind scale, so it says weather immediately — and Anvil's job is scales and bands       | Sounds like a person; three syllables        |
| **Stevenson**| The louvred screen every Bureau observation is taken inside. Exactly on-domain            | Also sounds like a person                   |
| **Southerly**| The southerly buster — the wind change that decides South Australian fire days            | Long, and it names one phenomenon rather than the whole |

**A:**

---

## Answer log

| Round | Date              | Asked | Answered | Decisions      | Left blocking |
|-------|-------------------|-------|----------|----------------|---------------|
| 1     | 15 September 2026 | 10    | 10       | W-001 – W-014  | 3             |
| 2     | 15 September 2026 | —     | —        | W-015 – W-024  | 5             |

**Round 2** was not asked, it was told. The lift-and-shift is off ([W-015](#w-015)): Anvil is a
greenfield Python service in Docker, built to a product definition — now plus seventy-two hours, four
blocks, at a point — with a pluggable model beneath it and five endpoints on top. Curing comes off
satellites rather than a keyboard ([W-023](#w-023)), the model is meant to grow ([W-024](#w-024)), and
the thing finally has a name that does not collide with the Hub's ([W-016](#w-016)).

Round 1's three blockers all found homes: labelling became [A2](#a2) and got harder, the band tables
became [C4](#c4) and got easier, and the curing write path became [D5](#d5) and got smaller.

Five new blockers, and they are architecture rather than approval: whether sources blend or one wins
([A1](#a1)), what provenance looks like when they do ([A2](#a2)), whether the caller picks fields
([A3](#a3)), which sources are in the first cut ([A4](#a4)), and whether a licensed curing product
actually exists ([A5](#a5)).
