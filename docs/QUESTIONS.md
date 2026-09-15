# Weather as its own service — the questions that decide it

Companion to the scope change put on **15 September 2026**: move the weather capability out of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database) and onto its own server, so that the
Hub becomes a consumer of it.

**Nothing here is decided.** Every entry carries a default so that silence still moves, but a default
is a starting position, not an answer. **Section A is eight questions long and it blocks approval** —
each one is a way the plan fails quietly if it is wrong, and none of them can be answered by writing
code first.

The house convention is the Hub's, deliberately: this document should read like
[the Hub's own question rounds](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/QUESTIONS.md),
because the decisions it takes will end up cited from the same decision table.

---

## How to answer

| Write     | Means                                                                  |
|-----------|------------------------------------------------------------------------|
| `default` | Accept the stated default — it becomes the decision                    |
| `skip`    | Stop asking, keep the assumption, accept it is unexamined              |
| `?`       | Expand the recommendation before deciding                              |
| `unsure`  | Park it, keep the default, but it stays open                           |

Answers get applied to this document and logged at the bottom; nothing lives only in chat.

---

## The proposal, as put

| Claim                        | As stated                                                                                             |
|------------------------------|-------------------------------------------------------------------------------------------------------|
| **Size**                     | ~6,200 lines, the largest single component in the Hub                                                 |
| **Scope**                    | Four external providers, drought integration, the fire and flood indices                              |
| **What is coming**           | Live Bureau station data, a long-term historical archive, forecast verification and tuning            |
| **The Hub afterwards**       | A simple consumer: asks about a location, caches the answer for as long as the service says to        |
| **Consumers afterwards**     | Unchanged — same endpoint, same fields                                                                |
| **The benefits**             | Independent pace, a weather fault degrades to a labelled old reading, a real modelling platform       |
| **The ask**                  | One low-cost server (small VPS or a Pi) and the development time to separate cleanly                  |

---

## What the code says today

Measured on `main` at `b8ef6dd`, 15 September 2026.

| Where                                          | Main  | Tests |
|------------------------------------------------|-------|-------|
| `hub-core/…/weather` + `core/fuel/GrassFireDanger` | 1,852 | 911   |
| `hub-services/…/weather`                       | 3,639 | 504   |
| `hub-managers/…/weather`                       | 315   | —     |
| `hub-layers/…/weather`                         | 553   | —     |
| `hub-app/…/console/Weather*`                   | 221   | 96    |
| **Total**                                      | **6,580** | **1,511** |

Four tables — `weather_anchor`, `drought_cell`, `river_cell`, `weather_call` — and one configuration
tree, `hub.weather`, with fifty-four defaulted keys across `cache`, `governor`, `refresh`, `fire`, `drought`
and `flood`.

### Five findings, before any question is asked

**F1 · Weather is not consumed in one place. It is consumed in five, and only one of them is an
endpoint.**

| Consumer                                  | How it consumes                                                      |
|-------------------------------------------|------------------------------------------------------------------------|
| `WeatherManager`                          | `weather.at()`, attaches the `weather` and `forecast` components       |
| `MetricsManager`                          | `weather.at()` in-process, on `WeatherEvent`, to compute GFDI          |
| `IncidentWatchStatistics`                 | `weather.at()` at each agency centroid, inside a statistics build      |
| `WeatherLayerController`                  | `GET /api/weather`, `GET /api/weather/coverage.geojson`                |
| `MapController` · `MetricsController` · `WeatherController` | the console screens, on the session rather than a key |

Plus `UsageService`, which imports `WeatherBudget` to report spend, and `Vocabulary`, which publishes
`FireDanger.BANDS`, `Kbdi.BANDS` and `WeatherBands` to consumers as enum tables so that
[nothing downstream computes them](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/23-phase-z-the-consumer-computes-nothing.md).

*"The Hub becomes a simple consumer" is the proposal's load-bearing sentence, and today the Hub is
five consumers, two of which are on the incident path.*

**F2 · MET Norway is documented but not built.** [09 §9.1](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/09-weather-and-metrics.md)
lists four providers. The code has two classes — `OpenMeteoProvider` and `GoogleWeatherProvider` —
with `OpenMeteoBomProvider` a nineteen-line subclass pointing at `/v1/bom`. The default order is
`{open-meteo, google}`. **The only free provider whose licence permits commercial use does not
exist.** That matters to Section F more than it matters to the split.

**F3 · Two of the grassland index's three non-weather inputs are Hub registers.** Fuel class comes
from the CFS fuel-type layers; fuel load and curing come from `MetricsRegisters`, which an operator
fills in on `/console/metrics` because no open API publishes curing for South Australia. GFDI is
therefore a computation that straddles whatever line gets drawn.

**F4 · The mechanism the proposal names for caching already exists and is already ignored.**
`WeatherReport.expiresAt` is documented as a provider-supplied ceiling; `WeatherCache.store` writes
it, and `find` filters on age alone
([20 · Build log](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/20-build-log.md)).
"Caches the answer for as long as the service tells it to" is a new behaviour, not a preserved one.

**F5 · The cache is three-dimensional and proximity-keyed, not point-keyed.** An anchor serves every
request within a combined horizontal-and-vertical reach for a TTL, which is why twenty appliances on
one fire ground cost one upstream call. A Hub-side cache keyed on the point it asked about is a
different object with a different hit rate, and the reach parameters mean nothing to it.

---

## Status

| Section                                   | Questions | Blocking |
|-------------------------------------------|-----------|----------|
| **A — Blocking**                          | **8**     | **8**    |
| B — Where the line falls                  | 7         | —        |
| C — The contract between the two          | 6         | —        |
| D — Failure and degradation               | 5         | —        |
| E — The capabilities that justify it      | 6         | —        |
| F — Licence, allowance and cost           | 5         | —        |
| G — The server itself                     | 6         | —        |
| H — Migration, sequencing and rollback    | 5         | —        |
| I — The counter-case                      | 4         | —        |
| **Total**                                 | **52**    | **8**    |

---

# Section A — Blocking

*Eight. Each one can invalidate the plan rather than adjust it.*

---

**A1. Where exactly does the line fall, given five in-process call sites and not one?**

The proposal's picture is one arrow: Hub asks, service answers. The code has `MetricsManager` and
`IncidentWatchStatistics` calling `WeatherService.at()` directly, on threads that are doing something
else, and the console reading `WeatherCache` internals to draw the coverage rings.

Three shapes are available:

|   | Shape                                                                                            | Costs                                                                        |
|---|---------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| 1 | **One seam.** `WeatherService` becomes an HTTP client behind the same Java interface; all five call sites are unchanged | The Hub keeps the interface, the types and the console. Smallest diff, smallest benefit |
| 2 | **Two seams.** The manager and the components move to HTTP; metrics and statistics keep an in-process facade over the cached answer | Honest about who needs freshness. Two code paths to reason about |
| 3 | **Everything out.** Weather including GFDI, the console screen and the coverage layer all live on the other server | The largest benefit and the largest consumer-visible change. Contradicts "nothing consumers see would change" |

*Why it matters:* it decides the size of the job by roughly an order of magnitude, and it decides
whether the claimed benefit — the Hub stops carrying weather's complexity — is actually delivered.
Shape 1 moves the *upstream calls* off the Hub and leaves ~1,900 lines of types, JSON shaping and a
console screen behind, which is a real but much smaller win than the proposal describes.

*Default:* **shape 2.** The incident path goes over the wire; the read-only console and statistics
reads go through a thin cached facade so a statistics build never blocks on a remote call.

**A:**

---

**A2. Does the grassland index move, when two of its three non-weather inputs are Hub registers?**

GFDI needs temperature, humidity and wind — weather — plus fuel class off the CFS layers, a fuel-load
register and a per-district curing figure an operator types in. It lives in `MetricsManager` today by
decision ([D-187](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/16-decisions.md)),
precisely because *the three inputs that are not weather are not weather*.

| If GFDI stays in the Hub                          | If GFDI moves to the weather service                        |
|---------------------------------------------------|--------------------------------------------------------------|
| The Hub still needs the hourly series, not just a summary — a larger payload on every refresh | The weather service needs curing, fuel load and a fuel-class lookup pushed to it, or it needs the CFS layers too |
| D-187 survives untouched                          | D-187 is reopened, and the operator's curing screen now feeds a second system |

*Why it matters:* it is the one place where the domain genuinely does not divide, and whichever way
it goes, something crosses the wire that the proposal does not mention.

*Default:* **GFDI stays in the Hub.** The weather service serves the hourly series; the Hub keeps the
registers and the arithmetic. The contract in Section C must therefore carry hourly temperature,
humidity and wind, not a summary.

**A:**

---

**A3. Is live Bureau of Meteorology station data actually available to us?**

This is the first of the three capabilities the proposal is *for*, and
[09 §9.1](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/09-weather-and-metrics.md) is
explicit: `api.weather.bom.gov.au` **exists, works, and its terms forbid third-party use without
written permission.** That is why ACCESS-G is reached through Open-Meteo instead. Separately, fifteen
FTP products on `ftp.bom.gov.au` — including `IDS60920.xml`, the South Australian observations — were
removed by [D-141](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/16-decisions.md), as a
*transport* decision rather than a licence one.

So which is being proposed?

| Route                              | Status                                                                     |
|------------------------------------|------------------------------------------------------------------------------|
| `api.weather.bom.gov.au`           | Forbidden by its own terms without written permission. Do we have any?      |
| `ftp.bom.gov.au` observation products | Available. Deleted deliberately as a transport in D-141, not as a licence   |
| A written permission request       | Possible. Unknown timeline, and the answer may be no                        |
| Open-Meteo's station-adjacent data | Model output, not observations. Does not satisfy the verification use       |

*Why it matters:* the headline new capability may be legally unavailable, and the fallback — the FTP
products — is a decision the Hub already took the other way. **If the answer is "the FTP products
come back", that is a reversal of D-141 that should be argued on its merits and does not need a new
server to happen.**

*Default:* **the FTP observation products**, reinstated on the weather service only, with a written
permission request to the Bureau raised in parallel and the API left alone until it answers.

**A:**

---

**A4. What is a forecast scored against, and does the scoring need Hub data flowing backwards?**

"Measure our forecasts against what actually happened" needs a definition of *what actually
happened*. Three candidates, and they are not equivalent:

| Truth                             | Gives                                                    | Needs                                            |
|-----------------------------------|-----------------------------------------------------------|--------------------------------------------------|
| Station observations              | Temperature, humidity, wind error at a station            | A3 to be answered yes                            |
| The reanalysis archive            | A gridded after-the-fact estimate at any point            | Nothing new. But it is a model scoring a model   |
| Incident outcomes                 | Whether a high-index day produced fires                   | `incident_archive` — **Hub data, flowing to the weather service** |

The third is the only one that scores the thing we actually care about, and it inverts the
proposal's direction of travel: the Hub is no longer a pure consumer, it is also a supplier.

*Why it matters:* a bidirectional link is a different system from a one-way one — it changes the
privacy surface (incident locations and times leaving the Hub), the coupling, and the failure
analysis. And if the answer is "reanalysis only", the honest description of the capability is *model
skill scoring*, not *tuned for our own conditions*.

*Default:* **stations and reanalysis for input verification; incident outcomes stay in the Hub** and
are joined there, offline, on request. No incident data crosses to the weather server in the first
version.

**A:**

---

**A5. Two caches, two clocks — what does a consumer see, and can it still tell stale from current?**

Today one line of English carries the whole provenance: *"cache: anchor 15.0 km away, 4 min old"*.
After the split there are two ages: how old the reading was when the weather service served it, and
how long the Hub has been holding that answer.

[13 · Concerns](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/13-concerns.md) names
this as the failure the system cares about most — *stale looks identical to current unless something
says otherwise* — and the proposal adds a second place for it to hide.

*Why it matters:* it is the one guarantee the weather feature was designed around, and it degrades by
default rather than by mistake. A field added later is a field consumers have already learnt to
ignore.

*Default:* the answer carries **both** ages explicitly — `observedAge` from the service and `heldFor`
from the Hub — and the `decision` sentence names both: *"cache: anchor 15.0 km away, 4 min old, held
here 3 min"*. Neither is derivable from the other and neither may be dropped.

**A:**

---

**A6. What does an incident's weather do when the weather service is unreachable?**

The proposal says a weather-side problem "degrades to a clearly-labelled older reading rather than
disrupting incident processing", which is the right instinct and not yet a specification. Today the
degradation ladder is internal: providers in order, then a stale anchor up to `max-stale` of three
hours, labelled. After the split there is a new rung — the network between two machines — and the
Hub's own ceiling on it is undefined.

|                                  | Question                                                                    |
|----------------------------------|-------------------------------------------------------------------------------|
| A new incident, service down     | No `weather` component at all, or an empty one saying why?                  |
| An open incident, service down   | Component frozen at its last value, or retracted?                           |
| How old is too old               | Does the Hub have its own `max-stale`, and is it three hours or longer?     |
| What FireBuddy sees              | A missing block, or a block with an age it can render?                      |
| Recovery                         | Does the next successful call backfill the incidents that missed one?       |

*Why it matters:* "degrades gracefully" is a claim that has to be true on the worst day, and the
worst day is the one where the fire ground and the outage coincide. A frozen wind direction that does
not say it is frozen is worse than no wind direction.

*Default:* **freeze, never retract**; the component keeps its last value with `heldFor` growing and a
`degraded: true` flag; the Hub's own ceiling is **six hours**, after which the block is served empty
with a reason rather than served old. Recovery backfills on the next sweep, not immediately.

**A:**

---

**A7. Does moving weather to a shared service change the Open-Meteo licence answer?**

Open-Meteo's free tier is **CC BY 4.0 and non-commercial**. Today that is a property of one
deployment calling an API for its own use. A dedicated weather server that answers questions for the
Hub — and, plausibly, later for FireBuddy, IncidentWatch and PropertyWatch — starts to look like
redistribution, and PropertyWatch is explicitly a *paid* report
([25](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/25-propertywatch-consumer-contract.md)).

Compounding it: per **F2**, MET Norway — the free provider that *does* permit commercial use, and the
whole reason the provider order has a commercially-safe rung — is documented and not built. Turning
Open-Meteo off today leaves only Google, which bills.

*Why it matters:* if a commercial consumer is coming, the split is the moment the licence question
stops being theoretical, and the mitigation the design already chose does not exist in code.

*Default:* **build MET Norway before the split, not after**, so the provider order has a real
commercially-licensed rung; treat the weather service as internal-only until then; attribute CC BY
4.0 on every answer that carries Open-Meteo data, through both hops.

**A:**

---

**A8. Who owns the allowance ledger, the budget and the governor — and what happens during the
transition when both sides can call?**

`WeatherBudget` writes every upstream call to `weather_call` before counting it; `WeatherGovernor`
moves the reuse windows off pressure against the day's allowance; `UsageService` reports the spend.
The allowance is per API key, not per machine. If both the Hub and the weather service can call
upstream during a cutover, **there are two ledgers and one allowance**, and the governor on each side
is reading half the truth.

*Why it matters:* the governor's entire purpose is the day Open-Meteo is down and Google's guarded
calls are carrying the load. That is exactly the day a split-brain budget overspends.

*Default:* **one writer, always.** The weather service owns `weather_call`, the budget and the
governor from the first day of the cutover; the Hub makes no upstream weather call at any point after
the switch, and the transition is a switch rather than an overlap. `UsageService` reads the weather
service's figures over the wire, or shows them as unavailable.

**A:**

---

# Section B — Where the line falls

**B1. Which files move, file by file?** A split argued at the level of "weather" is a split nobody can
cost. The inventory in *What the code says today* is the starting list; each of the five locations
needs a verdict. *Default:* `hub-services/weather` moves whole; `hub-core/weather` is duplicated as a
published contract (see B2); `hub-managers/weather` stays and becomes the client; `hub-layers/weather`
stays; the console screen stays and reads over the wire.
**A:**

**B2. Where do the shared types live?** `WeatherAnswer`, `WeatherJson`, `Conditions`, `FireWeather`,
`FloodWeather`, `DroughtIndex`, `Band`, `FireDanger.BANDS`, `Kbdi.BANDS`, `WeatherBands` are used on
both sides and published to consumers through `Vocabulary`. Options: a published Maven artefact, a
git submodule, or duplicate-and-contract-test. *Default:* **duplicate, with a golden-payload contract
test on both sides.** A shared artefact re-couples the release cycles the split exists to separate.
**A:**

**B3. Does terrain move?** `WeatherCache`, `DroughtService` and `FloodService` all call
`ElevationService` to sample true ground height, which is what makes the reach three-dimensional. The
height map is a file-backed source on the Hub's disk. *Default:* the weather service gets its own copy
of the tiles — it is static data and a second copy is cheaper than a second network hop on the lookup
path.
**A:**

**B4. Does the Hub keep an anchor cache, or a plain one?** Per **F5** these are different objects.
*Default:* **plain.** Point-keyed, short-TTL, honouring the service's stated expiry. The anchor cache
is the weather service's job and duplicating it puts the same subtle bug in two places.
**A:**

**B5. Do the four tables move, and does the Hub's schema shrink?** *Default:* `weather_anchor`,
`drought_cell`, `river_cell` and `weather_call` move to the weather service's own database and are
dropped from the Hub's. The Hub keeps only what it caches, which may be nothing on disk at all.
**A:**

**B6. Does the console weather screen move?** `/console/weather`, `/console/map/weather.geojson` and
the probe. *Default:* the screens stay in the Hub and read the weather service's status endpoint, so
there is one console. The weather service gets no UI of its own.
**A:**

**B7. What happens to `HostLimiter`?** Weather calls go through the Hub's shared per-host politeness
budget today. *Default:* the weather service carries its own copy; the two never call the same host
after the switch, so there is nothing to coordinate.
**A:**

---

# Section C — The contract between the two

**C1. Is the wire contract exactly today's `/api/weather` JSON?** *Default:* **yes, plus the two age
fields from A5 and the hourly series from A2.** Starting from the shape consumers already read is the
cheapest way to keep the proposal's "nothing changes downstream" promise honest.
**A:**

**C2. Does the Hub proxy `/api/weather`, or do consumers get a new host?** The proposal says
consumers keep reading the same endpoint, which means the Hub proxies — and stays on the critical
path for weather regardless. *Default:* **the Hub proxies**, from its cache where it can. Pointing
consumers at a second host is a consumer-visible change and a second API-key regime.
**A:**

**C3. How does the Hub authenticate to the weather service?** *Default:* the same API-key filter the
Hub already uses for consumers, in the other direction, over a private network path (see G3).
**A:**

**C4. How does the service tell the Hub how long to cache?** Per **F4** the field exists and is
ignored. *Default:* an explicit `cacheUntil` instant on every answer, derived from the anchor's TTL
and the provider's own expiry, whichever is sooner. The Hub honours it and never invents its own.
**A:**

**C5. Is the call synchronous on the incident path, and what is the timeout?** Weather attaches when
an incident is raised. *Default:* asynchronous — the incident is created without weather and the
component attaches when the answer arrives, with a **two-second** timeout on the call and no retry
inside the request.
**A:**

**C6. How does a field get added without breaking the Hub?** *Default:* additive-only, unknown fields
ignored on both sides, and the golden-payload test from B2 is the gate. No version negotiation.
**A:**

---

# Section D — Failure and degradation

**D1. What does "clearly-labelled" mean, field by field?** *Default:* `degraded`, `heldFor`,
`observedAge`, and the `decision` sentence naming the reason in English. All four on every block,
never only on the envelope.
**A:**

**D2. Does the Hub have its own `max-stale`?** *Default:* six hours, per A6, configurable, and
surfaced on the console rather than only in the payload.
**A:**

**D3. Does a weather outage show up as an incident-processing problem?** *Default:* never. Weather
failures log and raise on the weather panel; they do not fail an incident write, a merge or a
statistics build.
**A:**

**D4. Does the Hub start when the weather service is down?** Startup today has a `REHYDRATE` step for
anchors and the call ledger inside a gated phase sequence. *Default:* yes — the weather client's
startup step is best-effort and never gates readiness.
**A:**

**D5. Who is told, and how quickly?** *Default:* the existing Pushover channel, once per outage
rather than per failed call, with a recovery notice.
**A:**

---

# Section E — The capabilities that justify it

**E1. Which Bureau product, exactly?** Follows A3. *Default:* `IDS60920.xml` and the interstate
equivalents, ten-minutely, plus a written permission request for the API.
**A:**

**E2. What does the historical archive hold, and how big does it get?** Ten years at how many points,
at what resolution, in what form — raw payloads, parsed series, or daily aggregates? *Default:*
**parsed daily series plus hourly for the last two years**, at drought-cell resolution, which is
tens of megabytes rather than tens of gigabytes and fits the hardware in G1.
**A:**

**E3. What is scored, at what horizon, and against what baseline?** A skill score is meaningless
without a baseline — persistence and climatology are the usual two. *Default:* temperature, humidity,
wind and rain at 6, 24 and 72 hours, scored against persistence, per provider.
**A:**

**E4. Does "tune for our own conditions" mean a bias correction on the inputs, or a change to the
index?** *Default:* **bias correction on the inputs only.** FFDI is McArthur Mk5 in Noble, Bary and
Gill's published form and is checked against a hand-worked value; changing the index means the number
is no longer the one everybody else means by FFDI.
**A:**

**E5. Does a corrected number stay labelled as ours?** *Default:* yes — a corrected reading carries
`corrected: true` and the correction's basis, and the raw model value travels beside it.
**A:**

**E6. Does any of this need to be on the incident path?** *Default:* no. Archive, scoring and tuning
are offline jobs; the incident path reads only their outputs. This is the strongest argument in the
proposal's favour and it should be stated that way.
**A:**

---

# Section F — Licence, allowance and cost

**F1. How is CC BY 4.0 attributed through two hops?** *Default:* the attribution travels in the
answer and is rendered by every consumer that displays a reading, unchanged from today's disclaimer.
**A:**

**F2. Does PropertyWatch's paid report change the analysis?** Follows A7. *Default:* a paid consumer
may only be served from a commercially-licensed provider, enforced in the provider order rather than
by policy.
**A:**

**F3. Is MET Norway built before or after the split?** *Default:* **before**, per A7. It is a day's
work and it removes the licence question from the critical path.
**A:**

**F4. What does the transition cost in upstream calls?** A cold weather service re-spins every
drought cell at six allowance units each. *Default:* migrate `drought_cell` and `weather_anchor`
rather than re-spinning; a cold start is the fallback, not the plan.
**A:**

**F5. Which side holds the Google key and the monitoring credentials?** *Default:* the weather
service holds both; `/console/usage` reads its figures over the wire.
**A:**

---

# Section G — The server itself

**G1. Pi or VPS, and on what evidence?** A Pi is sufficient for the current workload — a few hundred
calls a day and 500 anchors. The archive and the scoring in Section E are the load that decides it.
*Default:* **a small VPS.** A Pi on a domestic connection is a single point of failure with no
out-of-band access on the day it matters, and the price difference is a few dollars a month.
**A:**

**G2. Its own Postgres, or the Hub's?** *Default:* **its own.** Sharing the database keeps the
coupling the split exists to remove, and the weather service's archive has a different growth profile
and a different backup need.
**A:**

**G3. What is the network path between them?** *Default:* the same Cloudflare tunnel pattern the Hub
already uses, so neither machine accepts anything inbound; the Hub reaches the weather service by
hostname and key.
**A:**

**G4. What is backed up, and what is merely re-derivable?** *Default:* the archive and the call ledger
are backed up; anchors, drought cells and river cells are re-derivable and are not.
**A:**

**G5. What does a restart cost?** *Default:* nothing, if anchors and cells persist and rehydrate the
way they do today. This should be verified, not assumed, before the switch.
**A:**

**G6. Does a second machine double the operational surface?** Honestly, yes. *Default:* accepted, on
the condition that D3 and D4 hold — the Hub never fails because the weather service did.
**A:**

---

# Section H — Migration, sequencing and rollback

**H1. Does this block Phase Z, FireBuddy or PropertyWatch?** *Default:* the split waits until
FireBuddy's owed items are closed. Weather is the one part of the stack that currently works and is
not on anybody's critical path — which is an argument for doing it now, and an argument for doing it
last.
**A:**

**H2. Cold start or data migration?** Follows F4. *Default:* migrate.
**A:**

**H3. What is the cutover?** *Default:* **shadow first.** The weather service runs alongside for a
week answering the same questions with no consumer reading it, and the two answers are diffed. Then a
switch, per A8 — no dual-calling period.
**A:**

**H4. What is the rollback, and for how long?** *Default:* the Hub's weather code stays in the tree
behind a flag for one month after the switch, then is deleted. A rollback that needs a revert of a
month-old deletion is not a rollback.
**A:**

**H5. Which tests move, and what replaces the fused ones?** 1,511 lines of weather tests. *Default:*
provider, cache, governor and index tests move; `WeatherPanelTest` stays; the golden-payload contract
test from B2 is new and runs on both sides in CI.
**A:**

---

# Section I — The counter-case

*Four questions whose honest answers might be "then do not do this".*

**I1. Is the Hub measurably heavier or riskier because weather is in it?** The claim is that weather
makes the incident system "heavier and riskier to change". The test is evidence: how many times has a
weather change broken a non-weather test, delayed a non-weather release, or caused an incident-path
defect? *Default:* if the answer is "none", the risk argument is theoretical and the capability
argument in Section E has to carry the proposal on its own.
**A:**

**I2. Would a module boundary inside the Hub buy most of the benefit for none of the cost?** The Hub's
module graph is already enforcement rather than convention, and weather already sits behind one
interface. *Default:* it buys the isolation but not the independent deployment or the archive's
storage profile — which means it is a real alternative only if E2 and E3 turn out small.
**A:**

**I3. Does a second server make the whole system less reliable, not more?** Two machines, a network
between them, and a new class of partial failure, against a fault-isolation benefit that only pays
out when weather breaks. *Default:* net positive **only if** D3 and D4 are built first and verified,
not asserted.
**A:**

**I4. What would we regret in a year?** *Default:* the likeliest regret is a contract frozen too
early around today's JSON, and the second likeliest is an archive that grew faster than the hardware.
Both are cheap to guard against now and expensive to fix later.
**A:**

---

## Answer log

*Empty. Nothing has been answered yet.*

| Round | Date | Questions closed | Decisions taken |
|-------|------|------------------|-----------------|
| —     | —    | —                | —               |
