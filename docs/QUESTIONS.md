# Anvil — the questions that decide it

The weather service, split out of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database) and rebuilt.

**Round 2 changed almost everything; round 3 settled it.** Round 1 decided a lift-and-shift of
working Java; round 2 reversed it into a **greenfield Python service in Docker**; round 3 answered the
five architectural questions that shape entailed. Thirty-four decisions stand.

**Section A is down to two**, and both are consequences of round 3 rather than survivors of it. The
architecture is settled: what is left is the shape of the answer on the wire.

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

James, 15 September 2026, rounds 1 to 3.

### Round 3 — the architecture

| #                           | Decision                                                                                                                                                             |
|-----------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <a id="w-025"></a>**W-025** | **Observations correct models; models never correct each other.** Built as one-source-wins first, with the resolver shaped so a per-variable correction step slots in when Bureau stations arrive. Answers [A1](#a1) |
| <a id="w-026"></a>**W-026** | **Provenance at all three levels** — per answer, per block, and **per variable**. Cheap now, impossible later, and it is what makes [W-025](#w-025)'s correction step reachable without a rewrite. Answers [A2](#a2) |
| <a id="w-027"></a>**W-027** | **A server manifest bounds what exists; a caller narrows by whole block.** `weather`, `drought`, `flood`, `fire`. No per-field selection. Answers [A3](#a3)            |
| <a id="w-028"></a>**W-028** | **The first cut is nine sources**: Open-Meteo forecast, archive, flood and ACCESS-G (off by default); Google; the height map; the CFS fuel layers; and satellite curing. **MET Norway and Bureau stations are not in v1.** Answers [A4](#a4) |
| <a id="w-029"></a>**W-029** | **A day is spent establishing whether a published curing product exists** — machine-readable, licensed — before anything is built on one. The manual register is built regardless. Answers [A5](#a5) |
| <a id="w-030"></a>**W-030** | **The name is Anvil.** Confirmed. Answers [Q1](#q1)                                                                                                                    |
| <a id="w-031"></a>**W-031** | **The proximity anchor stays**, with its three-dimensional reach. One concept across every source, and the reason twenty appliances on one fire ground cost one call. Answers [F2](#f2) |
| <a id="w-032"></a>**W-032** | **`xclim` is a dependency where it covers the quantity** — KBDI and the drought factor. **Our own code for FFDI, GFDI and spread**, pinned to Noble, Bary and Gill (1980), which is the form the Hub's tests are cut against. Answers [B5](#b5) |
| <a id="w-033"></a>**W-033** | **The incident path is asynchronous.** The incident is created without weather; the component attaches when Anvil answers. Two-second timeout, no retry in the request. **Incident creation can never be slowed or failed by Anvil.** Answers [E2](#e2) |
| <a id="w-034"></a>**W-034** | **The test fixtures are ported before any maths is written.** The Java's expected values — the hand-worked FFDI, the xclim-checked drought factors — become language-neutral JSON and Anvil's first test suite. Answers [I1](#i1) |

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

| Section                             | Open  | Blocking |
|-------------------------------------|-------|----------|
| **A — Blocking**                    | **2** | **2**    |
| B — The model                       | 6     | —        |
| C — The API                         | 6     | —        |
| D — Curing, fuel and the satellites | 7     | —        |
| E — The Hub side                    | 6     | —        |
| F — Storage and the cache           | 4     | —        |
| G — The manager page                | 4     | —        |
| H — Deployment, keys and the tunnel | 5     | —        |
| I — Correctness                     | 4     | —        |
| J — Later                           | 4     | —        |
| **Total**                           | **48**| **2**    |

Twenty-four questions have been answered across three rounds and become decisions; six were created
by round 3 — [A6](#a6), [A7](#a7), [B7](#b7), [D7](#d7), [E7](#e7) and [I5](#i5).

---

# Section A — Blocking

*Two. Both are consequences of round 3, and both are about the shape of the answer on the wire —
the thing that is cheap today and unfixable once consumers read it.*

---

<a id="a6"></a>**A6. Per-variable provenance across a 72-hour series — per timestep, or per series?**

[W-026](#w-026) is the right call and it has an arithmetic problem behind it. An answer carries 72
hourly steps across roughly fifteen variables. Attaching a source and an age to each of those is
**over a thousand provenance objects in a single response**, most of them identical.

|   | Shape                                                                                          | Costs                                                                        |
|---|---------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| 1 | **Per series.** One provenance entry per variable for the whole horizon — *"temperature: ACCESS-G, run 06Z, fetched 4 min ago"* | Small, readable, and correct almost always, because one source supplies a whole series |
| 2 | **Per timestep.** Every value carries its own                                                  | Exact, and enormous. Only earns its keep if two sources ever supply different hours of one series |
| 3 | **Per series, with exceptions.** One entry per variable, plus an override list for timesteps that differ | Small in the common case and correct in the rare one — at the cost of a shape with two ways to read it |

*Why it blocks:* it is the contract, and consumer contracts are being written against it now.

*Default:* **option 1 for the series, option 2 for `current`.** A forecast series comes from one model
run and one source; the *now* block is where a station correction would land under
[W-025](#w-025), so it is the one place a per-value source is worth carrying. If a series ever does
get spliced, that is option 3 and it can wait until something actually splices one.

**A:**

---

<a id="a7"></a>**A7. When an observation corrects a model, does the raw model value travel beside it?**

[W-025](#w-025) says stations will correct model estimates. Stations are not in v1
([W-028](#w-028)), so nothing is corrected yet — but by [A2](#a2)'s own logic the field has to exist
before consumers learn its absence.

|   | Shape                                                                        | Consequence                                                                  |
|---|----------------------------------------------------------------------------------|---------------------------------------------------------------------------------|
| 1 | **`value` is corrected; `modelValue` and `correction` travel beside it**     | Fully auditable. A consumer that ignores the extras still gets the better number |
| 2 | **`value` is corrected, `corrected: true` and a basis sentence, no raw**      | Smaller, and you cannot reconstruct what the model actually said                |
| 3 | **`value` is raw; the correction is a separate optional block**              | Nothing changes for existing consumers — and the default answer is then the worse number |

*Why it blocks:* it decides whether the hindcast scoring in [J2](#section-j--later) can ever run
against served answers, or only against re-fetched raw data.

*Default:* **option 1.** The raw value and the correction are two facts, and a system whose whole
discipline is *say how you know* should not throw one away. `correction` names the station, its
distance and its age.

**A:**

---

## Answered in round 3

**A1** — fusion. **Observations correct models; models never blend** ([W-025](#w-025)), built as
one-source-wins with the correction step designed for.
**A2** — provenance depth. **All three levels** ([W-026](#w-026)) — which is what created
[A6](#a6).
**A3** — field selection. **Manifest, narrowed by whole block** ([W-027](#w-027)).
**A4** — the source list. **Nine, without MET Norway or Bureau stations** ([W-028](#w-028)).
**A5** — curing. **Prove a published product exists first; build the register regardless**
([W-029](#w-029)) — the branches are [D7](#d7).

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

<a id="b5"></a>**B5. Is `xclim` a dependency or a reference?** **Answered** — [W-032](#w-032). A
dependency for KBDI and the drought factor; our own code for FFDI, GFDI and spread, because xclim's
fire-weather variants may not be the Noble, Bary and Gill form the fixtures are cut against.

<a id="b7"></a>**B7. Is the vertical term switched on at launch, and at what weight?** *(new)*
[W-031](#w-031) keeps the three-dimensional reach; the Hub ships it at `vertical-weight: 0`, which
reproduces a flat radius exactly, with 67 as the intended value — three hundred metres of climb
costing the same as twenty kilometres of travel. Greenfield, there is no old behaviour to reproduce.
*Default:* **ship at 67.** The reason it sits at 0 in the Hub is so the numbers could be compared
against the flat rule they replaced; Anvil has nothing to compare against, and the Hills and the
plains still do not share a wind.
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

<a id="d7"></a>**D7. What happens on each outcome of the curing investigation?** *(new)*
[W-028](#w-028) puts satellite curing in v1; [W-029](#w-029) says spend a day proving a published
product exists first. Those two need a gate between them. *Default:* **a licensed product exists** →
consume it, and the register becomes the override. **It exists but is not licensed for our use** →
route 2, derive from raw greenness, and [D3](#d3)'s disagreement log becomes the safeguard. **Nothing
usable exists** → the register is the source for v1, satellite work moves to [Section J](#section-j--later),
and GFDI is exactly as good as it is today rather than worse.
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

<a id="e2"></a>**E2. Is the call on the incident path synchronous?** **Answered — asynchronous**
([W-033](#w-033)). Incident creation can never be slowed or failed by Anvil.

<a id="e7"></a>**E7. What fills the gap [W-033](#w-033) opens, and who closes it?** *(new)* An incident
now exists, briefly, with no weather block — and if Anvil is down at the moment it is raised, for
longer than briefly. *Default:* the block is **absent, not empty** — consumers already tolerate
absence, because not every incident type gets weather at all — and the **staggered sweep is what
backfills it**, since it already walks open incidents whose turn has come. No separate retry queue,
no second mechanism.
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

<a id="f2"></a>**F2. What is the unit of the cache?** **Answered** — [W-031](#w-031). The proximity
anchor stays, with its three-dimensional reach. The weight it runs at is [B7](#b7).

<a id="f1"></a>**F1. Cold start or migration?** [W-008](#w-008) said migrate. Under [W-015](#w-015)
there is no shared schema to migrate into, and [W-014](#w-014) plus *"we would only be losing three
weeks"* makes the loss trivial. *Default:* **cold start.** The drought spin-up re-runs per cell on
demand — it is the expensive path, but it is bounded by activity and it buys a clean schema.
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

<a id="i1"></a>**I1. How do we know the Python arithmetic matches the Java?** **Answered** —
[W-034](#w-034). The expected values are ported first, as language-neutral JSON, and they are Anvil's
first test suite. Nothing is written against them afterwards to make them pass.

<a id="i5"></a>**I5. Where do the fixtures live?** *(new)* They are extracted from the Hub and
consumed by Anvil, and the Hub's Java is being deleted ([W-010](#w-010)). *Default:* **in this
repository**, under `tests/fixtures/`, extracted once before the deletion — because after the switch
the Java is gone and the values only exist here.
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

**Answered: Anvil** ([W-030](#w-030)). The alternates it was chosen over, kept because a name is
worth being able to defend later:

| Name         | For                                                                                     | Against                                     |
|--------------|-------------------------------------------------------------------------------------------|---------------------------------------------|
| **Anvil** ✓  | The cumulonimbus top; the shape a fire makes when it builds its own weather. Solid, hammered on. Unmistakably not "the Hub" | Does not say *weather* to a non-meteorologist |
| Beaufort     | A wind scale, so it says weather immediately — and Anvil's job is scales and bands       | Sounds like a person; three syllables        |
| Stevenson    | The louvred screen every Bureau observation is taken inside. Exactly on-domain            | Also sounds like a person                   |
| Southerly    | The southerly buster — the wind change that decides South Australian fire days            | Long, and it names one phenomenon rather than the whole |

---

## Answer log

| Round | Date              | Asked | Answered | Decisions      | Left blocking |
|-------|-------------------|-------|----------|----------------|---------------|
| 1     | 15 September 2026 | 10    | 10       | W-001 – W-014  | 3             |
| 2     | 15 September 2026 | —     | —        | W-015 – W-024  | 5             |
| 3     | 15 September 2026 | 10    | 10       | W-025 – W-034  | **2**         |

**Round 1** split the thing off. **Round 2** was not asked, it was told: the lift-and-shift is dead,
Anvil is a greenfield Python service built to a product definition, with a pluggable model and five
endpoints. **Round 3** answered the architecture that shape entailed.

Round 3's substance, in one place. Sources do not blend — but an observation correcting a model is
not two models averaging, so the resolver is built for a correction step it will not use until Bureau
stations arrive ([W-025](#w-025)). Provenance goes all the way down to the variable
([W-026](#w-026)), which is the decision that makes that step reachable and the one that created
[A6](#a6). A manifest bounds the API and a caller narrows by block, not by field
([W-027](#w-027)). Nine sources in the first cut, satellite curing among them and MET Norway and the
stations not ([W-028](#w-028)) — with a day spent first on whether a published curing product exists
at all ([W-029](#w-029)), which is the least verified idea in the plan and still the best one. The
anchor survives the rewrite ([W-031](#w-031)); `xclim` carries the drought maths and our own code
carries the fire indices, pinned to the paper the fixtures are cut against ([W-032](#w-032)); the
incident path goes asynchronous so Anvil can never slow a raise ([W-033](#w-033)); and the fixtures
are written before the arithmetic, not after it ([W-034](#w-034)).

**Two left, both about the wire.** How deep per-variable provenance goes across a 72-hour series
([A6](#a6)), and whether a corrected value carries the raw one beside it ([A7](#a7)). Both are cheap
now and unfixable once something reads them.

**Not a question, an action:** [W-029](#w-029)'s day of investigation. Nothing about curing can be
designed until it comes back.
