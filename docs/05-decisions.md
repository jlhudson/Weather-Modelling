# 05 · Decisions

[← Docs index](README.md)

Two decisions produced this repository. They were taken in the Hub, in the style of its
`docs/16-decisions.md`, and are quoted here verbatim from `docs/27-the-split.md` §27.3 rather than
paraphrased — a decision reworded is a decision nobody can check against the original.

Below them, the three this service took for itself. `D-nnn` references throughout the code point at
the Hub's decision log; `W-n` references point here.

---

## Taken in the Hub

> **D-242 · Three applications, three databases, HTTP between them.** Hub 8080, Operations 8081,
> Weather 8082. One Postgres instance to start, one database each. No cross-database reads. Placement
> (one VPS or three) is configuration, never architecture.

> **D-249 · Weather holds the cache; the Hub holds the question.** `WeatherManager`'s judgement about
> *when* a point is worth asking about stays in the Hub. The anchor cache, the provider chain, the
> budget governor and the drought accumulators go to Weather. The Hub calls every time and does not
> cache — until it does (§27.10).

---

## Taken here

### W-1 · Terrain comes from Open-Meteo's elevation endpoint, not from a tile store

**The decision.** `au.weather.terrain.ElevationService` resolves a height by calling
`https://api.open-meteo.com/v1/elevation` and memoising the answer permanently, keyed on the point
rounded to four decimal places, capped at twenty thousand entries. The Hub's `services/terrain` — the
slippy-tile downloader, the disk volume, the prefetch job, the sampler, the profiles, the horizons and
the viewshed coverage — did not come across.

**Why.** The tile store exists to answer a different question. MeshCore needs a *surface*: heights
every few metres along a path, thousands of samples per question, which only a local tile answers
affordably. The weather cache wants **one number per anchor**, at most five hundred anchors alive at
once, each asked about once and then remembered for the life of the process. That is a memo over a
free HTTP endpoint. Carrying three thousand lines across to serve it would have brought a disk mount,
a prefetch UI and a licence attribution into a service with no map to draw them on.

**What it costs.** A height costs a round trip the first time instead of a disk read, and heights the
tile store would have had for free now arrive one at a time. That is why the two-method contract
`WeatherCache` already had — `cached()` never calls out, `at()` may — was preserved exactly rather
than collapsed: `cached()` is in the lookup path, and a round trip in front of every cache hit would
be indefensible. It is also why the elevation host shares `api.open-meteo.com`'s existing budget
rather than declaring a new one.

**What it does not cost.** Nothing in the answer. Open-Meteo serves Copernicus DEM GLO-90, and four
decimal places is about eleven metres — finer than the 90 m posts underneath, so the rounding cannot
lose a distinction the data ever held.

**Where it fails.** A 429 or a dropped connection is remembered for that call only, never in the memo:
it is a fact about the minute, not about the point, and caching it would make one bad minute permanent.
The anchor keeps a null height and is retried on the next backfill pass — which is exactly what the
tile store did for a point outside coverage.

### W-2 · Open-Meteo only by default; the others stay in the repository, behind config

**The decision.** `weather.order` ships as `open-meteo, google`. MET Norway and the Bureau's ACCESS-G
through Open-Meteo are implemented, tested and out of the order.

**Why.** Agreed in docs/27 §27.4: *"Ship with Open-Meteo only. `WeatherProvider` is already an
interface with four implementations — keeping the other three in the repo behind config costs nothing
and means 'add MET Norway' is a config line, not a project."* Open-Meteo is free, generous and needs no
key. Google is last because it is the only one that bills, it is capped at ten thousand a month, and a
call costs three allowance units rather than one — so it is a fallback that has to be *reached*, not a
peer.

`open-meteo-bom` is out for a reason of its own rather than for caution: the Bureau has open-data
delivery suspended, and the provider rejects an all-null payload rather than serving one.

**The obligation this creates.** A fallback to a billed provider that nobody notices until the invoice
is not a fallback. Hence `/api/weather/status`, `/api/weather/spend`, `/api/weather/spend/daily` and
the console page: what each provider has spent, against what it is allowed, where a person can read it.

### W-3 · There is no incident half; the sweep governs and sweeps

**The decision.** `au.weather.startup.WeatherSweeper` rehydrates at boot, logs the governor's first
tuning, and then on every `weather.refresh.tick-interval` calls `govern`, `sweep` and — through
`WeatherCache.sweep` — the bounded terrain backfill. That is all it does. There is no incident walk,
no event bus and no attachment.

**Why.** It is D-249 made concrete. Everything else `WeatherManager` did was a judgement about
*incidents* — the stagger across open ones, the re-ask on an upgrade or a move beyond positional
uncertainty, the per-tick ceiling, the age at which an incident stops being refreshed — and this
service has no incidents and no way to acquire any. Bringing that half would have meant bringing
`IncidentView`, the enrichment matrix and the event bus, which is the Hub.

**What follows from it.** `WeatherProperties.Refresh` kept only `tickInterval`; its other six fields
were read by nothing here. The Hub's six-phase `PhasedStartup` was not brought either — there are two
ordered things in this service, so they are two `ApplicationRunner`s writing the same `StartupHistory`
the diagnostics block reads.

**What is still true.** The tick governs *before* it sweeps, not after, so the tick's own work uses the
numbers it just decided on. The governor no-ops unless its own interval has elapsed, so this costs a
comparison eleven times an hour and saves a second timer, a second event type and a second exception to
the wiring rules — which is the reason the Hub gave for putting the two together in the first place.
