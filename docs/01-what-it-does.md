# 01 · What it does

[← Docs index](README.md)

One point in, the weather out.

Ask it for a latitude and a longitude and it answers what the weather is doing there: the conditions
now, the fire danger, the flood picture, the drought behind them, and — when asked — the days and
hours ahead. Every answer says where it came from and how old it is, because an estimate stretched
22 km and taken 40 minutes ago is a different fact from one fetched at the point a moment ago, and a
consumer that cannot tell them apart will treat the first as the second.

Nothing here polls. There is no schedule of places to watch and no list of points of interest. A
reading exists because something asked for it.

---

## 1.1 The anchor cache

A weather reading is not a value keyed by a point. It is a value that is **approximately true over a
region for a while**. A cache keyed on exact coordinates would miss every time — two incidents in the
same suburb have different coordinates and identical weather.

So the key is proximity. The first request in an area creates an **anchor**; every request that lands
near enough to it and soon enough after it is answered from that anchor without an upstream call. A
fire ground with twenty appliances on it costs one call, not twenty.

**Two axes, and then a third.**

- **Time.** Inside `ttl` (30 minutes) an anchor answers outright. Past that and inside `maxStale`
  (3 hours) it is a last resort, served only when every provider has refused, and the age travels
  with it so nothing downstream mistakes it for current.
- **Distance.** Inside the reach — 15 km at the governor's floor — an anchor stands in for the point.
  That is deliberately *tighter* than the model grid, not looser: over Australia the finest model is
  about 15 km, so this stands in for roughly one grid cell rather than four. It costs calls, and it
  is affordable only because the free allowance is an order of magnitude larger than the appetite
  for it.
- **Height**, because distance alone gets the Hills wrong. Two points can be twenty kilometres apart
  on the map and four hundred metres apart vertically, and they do not share a temperature, a
  humidity or a wind. Reach is one number combining both, with a weight saying how much horizontal
  distance a metre of climb is worth. **It ships at zero**, which reproduces the old
  horizontal-only rule exactly — the reach numbers appear beside the distances they replace, and the
  weight is turned up once those numbers have been looked at.

Heights come from Open-Meteo's elevation endpoint, memoised per point rounded to four decimal places
for the life of the process — the memo is in memory, so a restart re-asks, a few anchors per sweep,
while the height already written on each anchor row survives. The Hub resolved them from a slippy-tile
store on a disk volume; that store exists to answer thousands of samples along a path for MeshCore, and
this service wants one number per anchor at most five hundred times. See W-1 in
[05-decisions.md](05-decisions.md).

The cache is bounded **by activity, not by area**: a few dozen anchors on a normal day, a few hundred
on a bad one, none at all over empty country. Past `maxAnchors` (500) the least-reused go first — an
anchor with forty hits is a fire ground, an anchor with none was a one-off lookup.

Every anchor survives a restart. So does the call ledger, so do the drought cells and the river
cells. Rehydrating costs nothing; re-fetching would cost allowance.

## 1.2 The provider chain and the budget

`WeatherProvider` is an interface with three beans behind it: `OpenMeteoProvider` (`/v1/forecast`,
the blended best-available model), `OpenMeteoBomProvider` (the same class against `/v1/bom`, the
Bureau's ACCESS-G) and `GoogleWeatherProvider`. Each carries its own `Spec`: endpoint, model, licence,
published allowance, and what one call costs in allowance units. None of that is a deployment choice,
so none of it is configuration. The one choice left is `weather.order` — which upstreams to use, and
in what sequence.

**It ships with Open-Meteo alone in front of Google.** Open-Meteo is free, generous
(600/minute, 10,000/day, 300,000/month) and needs no key. Google sits last on purpose: it is the one
that bills, it is capped at 10,000 a month, and a call costs three units rather than one. The Bureau's
ACCESS-G through Open-Meteo is in the repository and out of the order: `open-meteo-bom` is out because
the Bureau has open-data delivery suspended, and the provider rejects the all-null payload the endpoint
answers with rather than caching it. **MET Norway is not in this repository.** The Hub's docs/27
counted four implementations and three came across; what remains of the fourth is a mention in
comments and the `weather.contact` key that existed for its User-Agent rule — which nothing reads
([03 §3.1](03-configuration.md)).

**The budget is a ledger, not a counter.** Every upstream call is written to `weather_call` before it
is counted, so a restart does not forget a spend and "how much is left" is a fact rather than a hope.
A provider is retired at a **guard fraction** of its published limit rather than at the limit — 90%
by default — so the free tier is never actually exhausted, and a provider that fails cools down
before it is tried again.

`GET /api/weather/status` shows all of it: per provider, the limits, the spend per minute, hour, day
and month, whether it may be called and why not, and whether its free tier permits commercial use. A
fallback that quietly starts billing is not a fallback, it is a surprise.

## 1.3 The governor

The cache's two levers — how far a reading may be stretched, how long it stays good — decide what a
busy day costs. `WeatherGovernor` moves them, between a floor and a ceiling, on a signal it computes
each hour: allowance spent today against allowance the day *should* have spent by now, for whichever
provider is nearest its guard.

**This is insurance, not a daily tuner.** Against ten thousand units a day, a busy incident day
spends a few hundred, so pressure sits near zero and every value rests at its floor essentially
always. The machinery earns its keep on one kind of day: the one where Open-Meteo is down and
Google's guarded allowance is carrying the load. That is exactly the day the cache should stretch,
and exactly the day nobody has time to edit a configuration file.

It is deliberately slow and deliberately asymmetric. A dead band so nothing twitches on noise; eight
notches from floor to ceiling and one notch per hour, so a full traverse takes eight hours and nothing
lurches; and **two consecutive hours of headroom** required before stepping back *towards* the floor,
because relieving cost should be immediate and spending more should have to be earned. One quiet hour
is not evidence of a quiet day.

And it always says why, in one English sentence, on the console and in the API. A radius that moved
for a reason nobody can see is indistinguishable from a bug, and the person who has to tell those two
apart is looking at a map at two in the morning.

The time-to-live has a **hard floor of 30 minutes** that configuration cannot lower, because a
configurable floor is not a floor. Below half an hour the anchor cache stops being a cache and becomes
a proxy, and the models behind it publish a new run every one to six hours anyway — so the calls
bought below that line re-fetch the same forecast.

## 1.4 Drought

The fire index needs a **drought factor**, and a drought factor needs a soil moisture deficit, and a
deficit needs a year of history. So there is a spin-up: 365 days of daily rainfall and daily maximum
temperature, integrated into a **Keetch–Byram Drought Index** and then into a **Griffiths drought
factor**.

A year is not arbitrary. It is long enough for the assumed starting deficit to have washed out; much
less and the answer leans on that assumption — which is why the answer reports its own depth, and why
`estimated` is true and `basis` says so whenever the index fell back to the configured constant
instead.

It is the expensive part of the feature — a year of daily data is several allowance units, not one —
so it is cached far more coarsely than anything else: **one cell serves 50 km for a whole day**,
because soil moisture moves that slowly. The history comes from Open-Meteo's reanalysis archive, which
lags real time by a few days, and the gap is closed from the forecast endpoint with `past_days`.

The cells are the thing to be careful with. A year of integrated rainfall per cell is rebuilt from
nothing if it is lost. **Dump and restore them; do not re-derive** — see
[04-migration-from-the-hub.md](04-migration-from-the-hub.md).

## 1.5 Flood

Three inputs, and only one of them costs a call.

Antecedent rainfall comes free with the drought spin-up, which already has the daily series. Ground
saturation comes free with the weather report, which already carries soil moisture. **River discharge**
is the one that is a request of its own: modelled discharge of the largest river within about 5 km,
from GloFAS, with the recent past fetched alongside the forecast so a baseline and a trend can be read
from one call. A river running at four times its 92-day mean is the fact; the raw cubic metres are not.

The river cell radius is **5 km and deliberately tight**, far tighter than the weather anchor and an
order of magnitude tighter than a drought cell. Discharge belongs to a particular watercourse, and a
generous reuse radius would confidently report the wrong river.

Both halves degrade rather than vanish, and differently. With no drought cell the antecedent totals
are null but the forecast rain is still real, because it comes off the anchor's own daily series. With
no river cell the discharge is null but everything else stands. A block that showed nothing unless both
were present would hide the rain, which is the half that is always there.

## 1.6 What is deliberately not here

`docs/09` in the Hub drew this line and the split kept it:

> Everything that needs fuel load or curing — the grassland index, rate of spread — waits for a
> manager that has those, because they are not weather.

So these stayed in the Hub, with its metrics manager: **`GrassFireDanger`** and the grassland fire
danger index, **`FuelLoadEntity`**, **`GrassCuringEntity`**, **`FireDangerDayEntity`** and
`MetricsManager`. GFDI needs curing and fuel load. Neither is a weather observation, and neither
arrives from a forecast API.

Two more things stayed, for the same kind of reason:

- **The decision about *when* to ask.** `WeatherManager`'s judgement — refresh an incident when it is
  raised, when it is upgraded, when it moves further than its own positional uncertainty, otherwise on
  a stagger — is a judgement about *incidents*, and incidents are the Hub's (D-249). What came across
  is the fetching half: rehydrate at boot, then govern and sweep on a timer.
- **`WeatherEvent`.** It is an incident event, not a weather value.

And one thing is not here because it never needed to be: **there is no Hub-side cache**. The Hub calls
this service every time. That is the agreed shortcut of docs/27 §27.10 and it is a good one — the
anchor cache is doing the real work, and it is on the right side of the boundary.

Nor is there a Hub-side fallback (D-252, 17 September 2026). When this service does not answer — down,
unconfigured, or out of allowance with nothing cached near enough — the incident is written without a
weather block and is **owed** a reading: the Hub's `WeatherManager` keeps the owed set and drains it
first on every sweep, oldest first, closed incidents included, until the reading arrives or the
incident is older than its `max-incident-age`. The reading it eventually attaches is the one this
service gives *then*, not the one it would have given at the time — which is the gap
[06 §6.3.2](06-overhaul.md) proposes closing with history.
