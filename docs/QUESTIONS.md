# Weather as its own service — the questions that decide it

Companion to the scope change put on **15 September 2026**: move the weather capability out of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database) and onto its own server.

**Round 1 is answered.** Ten questions, fourteen decisions, and the shape of the thing has changed
twice in the answering. Section A is down from eight blocking questions to **three**, and all three
are consequences of the answers rather than survivors of them.

The house convention is the Hub's, deliberately: this reads like
[the Hub's own question rounds](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/QUESTIONS.md)
because the decisions it takes get cited from the same tables.

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

## What round 1 decided

James, 15 September 2026. Cited hereafter as `W-nnn`.

| #                              | Decision                                                                                                                                                                          |
|--------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <a id="w-001"></a>**W-001**    | **The justification of record is the development context budget, not production risk.** *"A little friction, but primarily it chews through context length… easily the best module we can pull out with little to no repercussions."* Success is therefore measured in **lines that leave the Hub**, and a design that isolates weather without removing it does not qualify |
| <a id="w-002"></a>**W-002**    | **Version one is a lift-and-shift.** *"Pull out what works into its own project, and have it start offering API data."* No new provider, no new source, no new capability in the first cut |
| <a id="w-003"></a>**W-003**    | **The Hub keeps one weather door.** `WeatherManager` stays, as an HTTP client with a cache. Every other Hub manager, service, layer and console screen asks it — none of them calls the weather API, and none of them holds weather state |
| <a id="w-004"></a>**W-004**    | **The weather service sets the cache expiry; the Hub obeys it.** The Hub never invents a TTL of its own                                                                             |
| <a id="w-005"></a>**W-005**    | **Service, maths and types all leave.** Providers, cache, governor, drought, flood, FFDI and `WeatherJson` go; the Hub keeps `WeatherManager`, its cache, and a slim answer type holding only the fields it reads. ~5,000 of 6,580 lines |
| <a id="w-006"></a>**W-006**    | **The grassland index moves to the weather service.** [D-187](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/16-decisions.md) is reversed: GFDI is computed beside FFDI, not in `MetricsManager` |
| <a id="w-007"></a>**W-007**    | **Its three non-weather inputs follow it.** `FuelTypeService` and the CFS fuel-type layers, `FuelLoadEntity`, `GrassCuringEntity` and `MetricsRegisters` all move. The weather service owns fire weather end to end, and any caller can get a grassland index |
| <a id="w-008"></a>**W-008**    | **The weather service owns its own Postgres.** `weather_anchor`, `drought_cell`, `river_cell` and `weather_call` migrate across rather than starting cold, and are dropped from the Hub's schema |
| <a id="w-009"></a>**W-009**    | **Degradation is three rungs and no extrapolation.** *"Either cache, nearest to cache, or no data."* A cache hit; failing that the nearest cached reading, labelled; failing that no weather at all. **No time ceiling** and no interpolation, ever |
| <a id="w-010"></a>**W-010**    | **Switch and delete — no shadow week, no feature flag** — *gated on this document settling the responsibility split first*: *"I am happy to delete and switch now, but we are still building exactly what the weather service and the hub are both responsible for"* |
| <a id="w-011"></a>**W-011**    | **Written Bureau permission exists.** Station data and the FTP products are nonetheless **not in version one**; they arrive once the cheap thing runs                              |
| <a id="w-012"></a>**W-012**    | **MET Norway, the historical archive, forecast verification and tuning are all post-v1.** So is the licence question that MET Norway answers, on the condition the service stays internal-only until a commercial consumer exists |
| <a id="w-013"></a>**W-013**    | **`/api/weather` stays a Hub endpoint**, served through `WeatherManager`'s cache. Consumers see no change and get no second host or key                                             |
| <a id="w-014"></a>**W-014**    | **The databases are disposable at James's discretion and by nobody else.** *"We can pretty much delete the databases at any time. You are not allowed to do this, but I can."* This lowers the cost of getting the migration wrong; it does not licence anyone else to skip it |

### What the answers changed about the plan

**The seam got simpler than any option offered.** The proposal implied one arrow and the code had five
call sites; the answer collapses them to one — `WeatherManager` is the Hub's single weather door, and
`MetricsManager`, `IncidentWatchStatistics` and the console all go through it rather than over the
wire. That is a better shape than the "two seams" on offer, and it makes **F1 a non-issue**.

**The scope got smaller and the move got bigger, at the same time.** Version one adds nothing
([W-002](#w-002)) — but GFDI, its registers and the CFS fuel layers now move too
([W-006](#w-006), [W-007](#w-007)), which takes roughly **a thousand lines more** out of the Hub than
the original proposal contemplated and turns the weather service into the owner of fire weather
rather than of weather.

**And it acquires a write path.** An operator enters curing on `/console/metrics`. Once the register
lives on the other machine, that screen writes across the wire — so the weather service is **not a
read-only service**, which is new, and is [A10](#a10) below.

---

## The split, as decided so far

*The thing James said is still being built. This is where round 1 leaves it.*

| The weather service owns                                                        | The Hub owns                                                            |
|----------------------------------------------------------------------------------|---------------------------------------------------------------------------|
| Every upstream call, and the only API keys that make them                       | Nothing upstream. It calls one host: the weather service                 |
| The anchor cache, its reach, its TTL and the governor                           | A cache whose expiry the weather service dictates ([W-004](#w-004))      |
| `weather_anchor` · `drought_cell` · `river_cell` · `weather_call`                | None of them                                                             |
| The drought integration and the KBDI spin-up                                    | —                                                                         |
| FFDI, **GFDI**, rate and direction of spread                                    | —                                                                         |
| The flood block: antecedent rain, forecast rain, saturation, river discharge    | —                                                                         |
| Fuel class off the CFS layers; the fuel-load and curing registers               | The **console screen** that fills the curing register, writing over the API |
| Terrain heights for its own three-dimensional reach                             | Terrain for everything else it already uses it for                       |
| The allowance ledger, the budget and the spend figures                          | `/console/usage`, reading those figures over the wire                    |
| — | `WeatherManager`: the one door, the cache, the refresh stagger, the sweep |
| — | Deciding **when** an incident is worth asking about                      |
| — | The `weather`, `forecast` and `metrics` components on an incident        |
| — | `/api/weather`, `/api/weather/coverage.geojson` and every consumer contract |
| — | `fire_danger_day` — the published CFS ratings, which are a ledger, not weather |
| — | Incident data. **None of it crosses** in version one                     |

**Three rows in the right-hand column are still arguable** and are [Section B](#section-b--where-the-line-falls).

---

## What the code says today

Measured on `main` at `b8ef6dd`, 15 September 2026.

| Where                                              | Main      | Tests     | Under W-005/6/7 |
|----------------------------------------------------|-----------|-----------|-----------------|
| `hub-core/…/weather` + `core/fuel`                 | 1,920     | 911       | leaves          |
| `hub-services/…/weather`                           | 3,639     | 504       | leaves          |
| `hub-services/…/fuel/FuelTypeService`              | 226       | —         | leaves          |
| `hub-managers/…/metrics` (registers, entities)     | 643       | —         | mostly leaves   |
| `hub-managers/…/weather`                           | 315       | —         | **stays**, rewritten as a client |
| `hub-layers/…/weather`                             | 553       | —         | **stays**       |
| `hub-app/…/console/Weather*` · `MetricsController` | 318       | 96        | **stays**       |
| **Total**                                          | **7,614** | **1,511** | **~6,400 leaves** |

Four tables, and one configuration tree — `hub.weather`, fifty-four defaulted keys across `cache`,
`governor`, `refresh`, `fire`, `drought` and `flood` — nearly all of which goes with the service.

### Five findings, and what round 1 did to each

| #  | Finding                                                                                                                                      | Now                                                                            |
|----|----------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------|
| F1 | **Weather is consumed in five places in-process, not one** — `WeatherManager`, `MetricsManager`, `IncidentWatchStatistics`, the layer controller, three console controllers | **Resolved by [W-003](#w-003).** One door; the other four ask it                |
| F2 | **MET Norway is documented and not built.** Two provider classes exist; the order is `{open-meteo, google}`                                   | **Deferred by [W-012](#w-012)**, with the internal-only condition attached      |
| F3 | **Two of GFDI's three non-weather inputs are Hub registers**                                                                                 | **Resolved by [W-007](#w-007)** — they move too                                 |
| F4 | **`WeatherReport.expiresAt` is written and never read**                                                                                      | **Becomes work.** [W-004](#w-004) makes service-dictated expiry the contract, so the field has to start being honoured on both sides |
| F5 | **The cache is proximity-keyed in three dimensions**; a point-keyed cache is a different object                                              | **Becomes work.** [W-009](#w-009)'s "nearest to cache" rung means the Hub's cache needs a nearest-match too, not just a lookup — see [A5](#a5) |

---

## Status

| Section                                   | Open  | Answered | Total | Blocking |
|-------------------------------------------|-------|----------|-------|----------|
| **A — Blocking**                          | **3** | 7        | 10    | **3**    |
| B — Where the line falls                  | 4     | 5        | 9     | —        |
| C — The contract between the two          | 4     | 2        | 6     | —        |
| D — Failure and degradation               | 3     | 3        | 6     | —        |
| E — The capabilities that justify it      | 0     | 6        | 6     | —        |
| F — Licence, allowance and cost           | 2     | 4        | 6     | —        |
| G — The server itself                     | 5     | 1        | 6     | —        |
| H — Migration, sequencing and rollback    | 3     | 3        | 6     | —        |
| I — The counter-case                      | 2     | 2        | 4     | —        |
| **Total**                                 | **26**| **33**   | **59**| **3**    |

Fifty-nine: the original fifty-two, plus the seven the answers created — [A9](#a9), [A10](#a10),
[B8](#b8), [B9](#b9), [D6](#d6), [F6](#f6) and [H6](#h6). Section E is answered in one stroke by
[W-002](#w-002) and [W-012](#w-012) — *later* is an answer.

---

# Section A — Blocking

*Three. All three are consequences of round 1, not survivors of it.*

---

<a id="a5"></a>**A5. Two caches, two clocks — and now no ceiling. What stops an old reading looking
like a current one?**

[W-009](#w-009) is the right call and it removes the one safeguard I had proposed. *"Either cache,
nearest to cache, or no data"* means **an arbitrarily old reading can be served**, and the six-hour
ceiling that would have caught it is gone. There is nothing wrong with that — a labelled old reading
genuinely beats nothing — but it makes the label the *only* thing standing between a consumer and a
day-old wind direction rendered as current.

There are now two ages where there was one, and neither is derivable from the other:

| Age              | Means                                                                |
|------------------|------------------------------------------------------------------------|
| `observedAge`    | How old the reading was when the weather service served it — anchor age, exactly as today |
| `heldFor`        | How long the Hub's own cache has been holding that answer since        |
| `offsetMetres`   | Already exists. Under the nearest-to-cache rung it can now be **large**, and it is the difference between "your suburb" and "the other side of the range" |

[13 · Concerns](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/13-concerns.md) names
this as the failure the system cares about most: *stale looks identical to current unless something
says otherwise.*

*Why it blocks:* it is cheap now and unfixable later — a field added in six months is a field every
consumer has already learnt to ignore, and the consumer contracts in
[23](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/23-phase-z-the-consumer-computes-nothing.md)
and [24](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/24-firebuddy-consumer-contract.md)
are being written against this shape right now.

*Default:* all three travel on every block, and the `decision` sentence names them in English —
*"cache: anchor 15.0 km away, 4 min old, held here 3 min"*, and on the second rung *"nearest cached
reading: 47 km away, 2 h 10 min old, no live answer"*. A reading served on the nearest-to-cache rung
carries `degraded: true` as well, because *nearest* and *near* are not the same claim.

**A:**

---

<a id="a9"></a>**A9. Where do the band tables come from once the types leave?**

[W-005](#w-005) sends `FireDanger.BANDS`, `GrassFireDanger.BANDS`, `Kbdi.BANDS` and `WeatherBands`
out of the Hub with everything else. But the Hub publishes those tables to consumers through
`Vocabulary`, and the whole point of
[Phase Z](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/23-phase-z-the-consumer-computes-nothing.md)
is that *the consumer computes nothing* — a consumer's colour axis and this system's rating cannot be
allowed to disagree.

|   | Option                                                                                     | Costs                                                                       |
|---|--------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| 1 | The weather service serves its bands on an endpoint; the Hub fetches them at startup and republishes through `Vocabulary` | One more startup dependency, and a cache for when it is down. Single source of truth |
| 2 | The Hub keeps a literal copy of the four band tables — a few dozen constants               | No dependency, and two places where Catastrophic-at-100 could drift apart      |
| 3 | Consumers read bands from the weather service directly                                     | Cheapest, and it breaks [W-013](#w-013) — consumers would see a second host    |

*Why it blocks:* it is the one place where [W-005](#w-005) collides with an existing commitment, and
option 2 is the tempting one that quietly reintroduces the class of bug Phase Z exists to prevent.

*Default:* **option 1.** Fetched at startup, cached to disk, and the Hub serves the last good copy
with its age when the weather service is down — the same honesty rule as the readings themselves.

**A:**

---

<a id="a10"></a>**A10. The weather service is no longer read-only. Who may write to it, and what
happens when the write fails?**

[W-007](#w-007) moves the curing and fuel-load registers, and an operator fills the curing figure in
by hand on `/console/metrics` from the CFS's weekly map — because no open API publishes curing for
South Australia. The screen stays in the Hub ([W-003](#w-003)); the register does not. So the Hub now
**writes** to the weather service.

That is a genuinely new property. The Hub's own API is read-only by
[D-044](https://github.com/jlhudson/The-Hub-Database/blob/main/docs/16-decisions.md), with narrow
stored-write exceptions argued one at a time (D-207, D-228). The weather service starts life with
one.

|                         | Question                                                                        |
|-------------------------|-----------------------------------------------------------------------------------|
| Who may write           | One key for the Hub, or a key per operator, attributed?                          |
| What is written         | Curing per district and fuel load per class. Anything else, ever?                |
| On failure              | Does the console show an error and lose the entry, or queue it?                  |
| Who is the record       | If the write succeeds and the Hub forgets, the figure only exists on one machine |
| Audit                   | Curing carries an observer, a date and a source today. Do those survive the move? |

*Why it blocks:* a district with no curing figure has no grassland index, and a curing entry that
silently failed to land looks exactly like a district nobody has updated.

*Default:* **one key, the Hub's, with the operator's name carried in the payload** so attribution
survives; a failed write is an error on the screen and nothing is lost, because the operator is
standing there; no queue, no retry, no shadow copy in the Hub. Observer, date and source move with
the row.

**A:**

---

## Answered in round 1

**A1** — the seam. **Answered better than asked** ([W-003](#w-003)): one door, not two seams.
`WeatherManager` is the only thing in the Hub that talks to the weather service.
**A2** — does GFDI move. **Yes** ([W-006](#w-006)), and its inputs with it ([W-007](#w-007)).
**A3** — Bureau station data. **Permission exists; not in v1** ([W-011](#w-011)).
**A6** — degradation. **Cache, nearest cached, nothing. No ceiling, no extrapolation**
([W-009](#w-009)).
**A7** — the Open-Meteo licence. **Deferred with MET Norway** ([W-012](#w-012)), internal-only until
a commercial consumer exists.
**A4** — what a forecast is scored against. **Not blocking any more**: verification is post-v1
([W-012](#w-012)), so it moves to [E3](#section-e--the-capabilities-that-justify-it). The question it
raised does not go away — the only truth worth scoring against may be incident outcomes, and that
would invert the direction of data flow.
**A8** — who owns the allowance ledger. **Settled by [W-008](#w-008) and [W-010](#w-010)**: the
weather service owns it, and a switch rather than an overlap means there is never a moment with two
writers.

---

# Section B — Where the line falls

**B1. Which files move.** **Answered** — [W-005](#w-005), [W-006](#w-006), [W-007](#w-007); the
inventory table above is the list.

**B2. Where the shared types live.** **Answered** — they leave ([W-005](#w-005)). The consequence is
[A9](#a9).

**B3. Does terrain move?** `WeatherCache`, `DroughtService` and `FloodService` sample `ElevationService`
for true ground height, which is what makes the reach three-dimensional; the height map is a
file-backed source on the Hub's disk, and the Hub keeps using it for everything else. *Default:* the
weather service gets **its own copy of the tiles** — static data, and a second copy is cheaper than a
network hop on the lookup path.
**A:**

**B4. What shape is the Hub's cache?** **Answered in part** — expiry is the service's
([W-004](#w-004)). What is open is whether it needs a nearest-match, and [W-009](#w-009) says it
does. See [A5](#a5).

**B5. Do the four tables move?** **Answered** — [W-008](#w-008).

**B6. Does the console move?** **Answered** — it stays ([W-003](#w-003)), and now writes
([A10](#a10)).

**B7. What happens to `HostLimiter`?** Weather calls go through the Hub's shared per-host politeness
budget today. *Default:* the weather service carries its own copy; after the switch the two never
call the same host, so there is nothing to coordinate.
**A:**

<a id="b8"></a>**B8. What is left of `MetricsManager` and the `metrics` component?** *(new)* With GFDI, spread, the
registers and the fuel layers gone, `MetricsManager` is a manager that consumes `WeatherEvent` and
copies a block onto an incident. The `metrics` row in the enrichment matrix is a consumer-visible
contract. *Default:* the component and its row stay exactly as they are — `WeatherManager` fills them
from the answer, and `MetricsManager` is deleted rather than hollowed out.
**A:**

<a id="b9"></a>**B9. Does `IncidentWatchStatistics` keep asking per agency centroid?** *(new)* It calls for weather
at each agency's centroid inside a statistics build. Through one door that is now a cache lookup per
agency on a background thread. *Default:* unchanged behaviour, but it may only read the cache — a
statistics build never triggers an upstream fetch.
**A:**

---

# Section C — The contract between the two

**C1. Is the wire contract today's `/api/weather` JSON?** Now more load-bearing: under
[W-005](#w-005) the Hub has no rich types left, so the wire shape *is* the model. *Default:* **yes,
plus** the ages from [A5](#a5), the hourly series, and the grass block from [W-006](#w-006). Starting
from the shape consumers already read is the cheapest way to keep [W-013](#w-013) honest.
**A:**

**C2. Does the Hub proxy?** **Answered** — [W-013](#w-013).

**C3. How do the two authenticate?** Now two directions, not one — reads, and the curing write from
[A10](#a10). *Default:* the same API-key filter the Hub already runs for its own consumers, pointed
the other way, over a private path.
**A:**

**C4. How is cache duration communicated?** **Answered** — [W-004](#w-004). The mechanism is
`cacheUntil` on every answer, and per **F4** it has to actually be read, which it is not today.

**C5. Synchronous on the incident path?** Weather attaches when an incident is raised, and that path
now crosses a network. *Default:* **asynchronous** — the incident is created without weather and the
component attaches when the answer arrives; two-second timeout, no retry inside the request.
**A:**

**C6. How does a field get added without breaking the Hub?** *Default:* additive-only, unknown fields
ignored both ways, and a golden-payload test on both sides is the gate. No version negotiation.
**A:**

---

# Section D — Failure and degradation

**D1. What "labelled" means, field by field.** **Folded into [A5](#a5)**, which is where it now
matters most.

**D2. Does the Hub have its own max-stale?** **Answered — no** ([W-009](#w-009)). No ceiling. That is
what makes [A5](#a5) blocking.

**D3. Can a weather failure break incident processing?** **Answered — never.** Implicit in
[W-009](#w-009): the worst outcome is no weather, and no weather is acceptable.

**D4. Does the Hub start when the weather service is down?** Startup has a `REHYDRATE` step for
anchors and the ledger inside a gated phase sequence, and under [A9](#a9) it may also want the band
tables. *Default:* yes — the weather client's startup steps are best-effort and never gate readiness.
**A:**

**D5. Who is told, and how quickly?** *Default:* the existing Pushover channel, once per outage
rather than per failed call, with a recovery notice. The weather panel shows the live state.
**A:**

<a id="d6"></a>**D6. How does an operator see which rung an answer came from?** *(new)* Three rungs
([W-009](#w-009)) and no ceiling means the console should show the distribution, not just the
readings. *Default:* the weather panel counts answers by rung over the last hour, so "everything is
coming off the nearest-cached rung" is visible before somebody notices the numbers look odd.
**A:**

---

# Section E — The capabilities that justify it

**All six answered by [W-002](#w-002) and [W-012](#w-012): later.** Version one adds nothing. Kept
here because they are the reason the server exists, and because two of them have a shape worth
remembering when they arrive.

**E1. Which Bureau product.** Permission exists ([W-011](#w-011)); FTP and station ingest are post-v1.
**E2. What the archive holds, and how big.** Post-v1. It is the load that decides
[G1](#section-g--the-server-itself), so it should be sized before the hardware is bought, not after.
**E3. What a forecast is scored against.** Post-v1 — and carrying [A4](#a4)'s unanswered core: station
observations and reanalysis score the *inputs*; only incident outcomes score the thing we care about,
and that would send Hub data the other way for the first time.
**E4. Bias correction or a changed index.** Post-v1. *Standing recommendation:* **inputs only.** FFDI
is McArthur Mk5 in Noble, Bary and Gill's published form and is checked against a hand-worked value;
change the index and the number stops being the one everybody else means by FFDI.
**E5. Does a corrected number stay labelled as ours.** Post-v1. Yes, when it comes.
**E6. Does any of this touch the incident path?** **No** — archive, scoring and tuning are offline
jobs. This is the strongest argument in the proposal's favour and deserves to be stated as one.

---

# Section F — Licence, allowance and cost

**F1. How is CC BY 4.0 attributed through two hops?** *Default:* the attribution travels in the answer
and is rendered by every consumer that displays a reading, unchanged from today's disclaimer.
**A:**

**F2. Does a paid consumer change it?** **Deferred** with [W-012](#w-012) — and the internal-only
condition is the thing that expires. PropertyWatch is the trigger to revisit.

**F3. MET Norway before or after?** **Answered — after** ([W-012](#w-012)).

**F4. Transition cost in upstream calls.** **Answered — migrate, don't re-spin** ([W-008](#w-008)).
[W-014](#w-014) makes getting this wrong survivable rather than expensive.

**F5. Which side holds the keys?** **Answered** — the weather service holds `GOOGLE_WEATHER_KEY`, the
monitoring credentials and the contact identity; `/console/usage` reads its figures over the wire.

<a id="f6"></a>**F6. What does the Bureau permission actually allow?** *(new)* [W-011](#w-011) says permission
exists. Before station data is built on it, its terms need recording somewhere durable — in
particular whether it permits **redistribution** to this system's consumers, which is a different
question from whether it permits use. *Default:* record the permission and its scope in this
repository when station work starts; assume use-only until read.
**A:**

---

# Section G — The server itself

**G1. Pi or VPS?** A Pi is ample for v1 — a few hundred calls a day and 500 anchors. The archive
(E2) is what decides it, and the archive is post-v1. *Default:* **a small VPS.** A Pi on a domestic
connection is a single point of failure with no out-of-band access on the day it matters, for a few
dollars a month.
**A:**

**G2. Its own Postgres?** **Answered — yes** ([W-008](#w-008)).

**G3. The network path between them.** *Default:* the same Cloudflare tunnel pattern the Hub already
uses, so neither machine accepts anything inbound.
**A:**

**G4. What is backed up?** [W-014](#w-014) says the data is disposable, which mostly answers this.
*Default:* nothing is backed up in v1 — anchors, cells and the ledger are all re-derivable, and the
curing register is the only human-entered data on the box. **That one is not re-derivable and should
be.**
**A:**

**G5. What does a restart cost?** *Default:* nothing, if anchors and cells rehydrate the way they do
today. Worth verifying rather than assuming, before the switch.
**A:**

**G6. Does a second machine double the operational surface?** Honestly, yes. *Default:* accepted —
[W-009](#w-009) and D3 mean the Hub never fails because the weather service did, which is the
condition that makes it worth it.
**A:**

---

# Section H — Migration, sequencing and rollback

**H1. Does this block Phase Z, FireBuddy or PropertyWatch?** [W-010](#w-010) gates the switch on the
responsibility split, not on other work. *Default:* the split lands before FireBuddy's owed items,
because [W-001](#w-001) means every week it waits is a week of context spent on weather the Hub no
longer needs to carry.
**A:**

**H2. Cold start or migration?** **Answered — migrate** ([W-008](#w-008)).

**H3. What is the cutover?** **Answered — a switch, no shadow week** ([W-010](#w-010)), gated on this
document.

**H4. Rollback?** **Answered — none.** Delete and switch ([W-010](#w-010)); the safety net is
[W-014](#w-014) and git.

**H5. Which tests move, and what replaces the fused ones?** 1,511 lines. Provider, cache, governor,
index and fuel tests move; `WeatherPanelTest` stays. *Default:* one golden-payload contract test runs
on both sides in CI, and it is written **before** the switch, not after — under [W-010](#w-010) there
is no flag to fall back to.
**A:**

<a id="h6"></a>**H6. In what order do the two repositories change?** *(new)* A switch with no flag means one
sequence works and the others leave a broken tree. *Default:* the weather service is stood up and
answering first; then the Hub's swap and deletion land as **one commit**.
**A:**

---

# Section I — The counter-case

**I1. Is the Hub measurably heavier because weather is in it?** **Answered** ([W-001](#w-001)): a
little friction, and mainly context. Recorded plainly because it changes the test of success — this
is justified by lines removed, so a design that leaves weather's vocabulary behind fails on its own
terms even if it ships.

**I2. Would a module boundary inside the Hub do?** **Answered by [W-001](#w-001) — no.** A module
boundary isolates code without removing it, and code that is still in the tree is still read. The one
argument against the split does not survive the reason for it.

**I3. Does a second server make the whole system less reliable?** Two machines, a network between
them, a new class of partial failure — against a fault-isolation benefit that only pays out when
weather breaks. [W-009](#w-009) is the mitigation and it is a good one. *Default:* net positive,
**conditional on [A5](#a5)** — an unlabelled old reading is worse than the outage it is covering for.
**A:**

**I4. What would we regret in a year?** *Default:* the likeliest regret is now **[A9](#a9)** — bands
that drifted apart between two systems and a consumer colouring Catastrophic wrong. Second is a
curing figure that was entered, failed to write, and was believed ([A10](#a10)). Both are cheap
today.
**A:**

---

## Answer log

| Round | Date              | Asked | Answered | Decisions            | Left blocking |
|-------|-------------------|-------|----------|----------------------|---------------|
| 1     | 15 September 2026 | 10    | 10       | W-001 – W-014        | 3             |

**Round 1**, James, in ten answers. The seam collapsed from five call sites to one door
([W-003](#w-003)); the scope shrank to a lift-and-shift ([W-002](#w-002)) while the move itself grew
by the grassland index and its registers ([W-006](#w-006), [W-007](#w-007)); the justification was
put on the record as context budget rather than production risk ([W-001](#w-001)); and degradation
was settled as three rungs with no ceiling and no extrapolation ([W-009](#w-009)) — which is what
promoted labelling ([A5](#a5)) to the most important open question in the document.

Seven questions were created by the answers: [A9](#a9), [A10](#a10), [B8](#b8), [B9](#b9),
[D6](#d6), [F6](#f6) and [H6](#h6).
