# Anvil — open questions

**Starting over.** Everything discussed before this is discarded — no decisions carried, no notes
kept.

---

## What it is

A small Python service. Given a latitude and a longitude, it answers:

| Block                   | What                                                    |
|-------------------------|---------------------------------------------------------|
| **Weather**             | Conditions now                                          |
| **Forecast**            | Conditions ahead                                        |
| **Fire index**          | The index now                                           |
| **Fire index forecast** | The index ahead                                         |

A cache inside the application answers anything close enough in **time**, **distance** and
**height**; anything else is a call to Google.

**Not in it:** flood, rivers, MET Norway, the Bureau, satellites, forecast verification, a management
page. Later, or never.

**In it, narrowly:** Open-Meteo's archive, for the rainfall history the drought factor needs — and
nothing else. The weather itself stays a pure Google pass-through.

---

## What Google gives us

Three endpoints on `https://weather.googleapis.com/v1`, billed separately — 10 000 free calls per
endpoint per month, so **one answer costs three calls**.

| Endpoint                       | Gives                                                          |
|--------------------------------|-----------------------------------------------------------------|
| `currentConditions:lookup`     | Now                                                             |
| `forecast/hours:lookup`        | Hourly, up to 240 hours                                        |
| `forecast/days:lookup`         | Daily, up to 10 days                                            |

Fields include `temperature`, `relativeHumidity`, `wind.speed`, `wind.direction`, `wind.gust`,
`precipitation.qpf`, `precipitation.probability`, `airPressure`, `cloudCover`, `uvIndex`,
`visibility`, `weatherCondition`. The daily forecast carries its own `daytimeForecast` block with the
same shape.

**That is everything the fire index needs but one number.**

---

## The drought factor, and where it comes from

McArthur Mk5, in the published form:

```
FFDI = 2.0 × exp( −0.450 + 0.987 ln(DF) − 0.0345 RH + 0.0338 T + 0.0234 V )
```

`T`, `RH` and `V` all come straight off Google. **`DF` — the drought factor, 0 to 10 — does not**, and
Google cannot supply it at any price: its history endpoint offers twenty-four hours.

**Two windows, not one.**

| Window       | For                                                                                               |
|--------------|-----------------------------------------------------------------------------------------------------|
| **20 days**  | The drought factor itself — the largest rain event in the window and how long since it (Griffiths 1999, corrected by Finkele et al. 2006) |
| **~365 days**| The soil moisture deficit **underneath** it. KBDI is a running integration from an assumed start, and the assumption only washes out if a wet season falls inside the window |

The year also yields **mean annual rainfall**, which the KBDI equation needs as a parameter — it
stands in for vegetation density, so a wetter climate dries *faster* per day than an arid one at the
same temperature. Reading that term the intuitive way inverts the index everywhere it is used.

**The entire input list is two daily variables:** rainfall total and maximum temperature.

**Open-Meteo has both, free and without a key.**

| Need                       | Source                                                                                   |
|----------------------------|--------------------------------------------------------------------------------------------|
| The long tail              | `archive-api.open-meteo.com/v1/archive` — ERA5 reanalysis, `daily=precipitation_sum,temperature_2m_max` |
| The last few days          | `api.open-meteo.com/v1/forecast?past_days=N` — the archive lags about five days. Join by date so the deliberate overlap corrects rather than doubles |
| Ground height              | `api.open-meteo.com/v1/elevation` — Copernicus 90 m DEM. **Unverified from here**, see [Q4](#q4)  |
| KBDI or the drought factor | **Nothing. Nobody publishes it.** Which is why it has to be integrated                      |

**Not a substitute:** Open-Meteo's model soil moisture is a different quantity. KBDI is the specific
empirical index McArthur calibrated FFDI against, and swapping in soil moisture yields a number that
is not FFDI.

**And it stays cheap.** The spin-up is one archive call plus one recent call **per cell per day**, on a
much coarser cache than the weather — drought is smooth, so cells can be tens of kilometres wide where
weather anchors are not. After the first spin-up for a cell, yesterday's deficit persists and steps
forward one day. The 365-day fetch happens once per location, not once per request.

**One caveat:** Open-Meteo's free tier is CC BY 4.0 and **non-commercial**. Fine now; it matters the
day something paid reads this.

---

## Questions

**Q1. How is the drought factor computed?** *Settled in principle — it is computed, from Open-Meteo's
archive. What is open is the spin-up length and the cell size.*

|                  | Recommended | Why                                                                                                  |
|------------------|-------------|--------------------------------------------------------------------------------------------------------|
| Spin-up          | **365 days**| Long enough that the assumed starting deficit washes out — *given a wet season*. In a rainless year it does not, so the answer must report its own depth and whether it completed |
| Drought cell     | **50 km**   | Far coarser than the weather cache, because the quantity is far smoother. One cell, one day           |
| Starting deficit | **Field capacity** (0 mm deficit, 203.2 mm at the dry end) | There is nothing else to start from, which is exactly why the spin-up has to be long |
| When incomplete  | **Say so**  | An `estimated` flag and the depth actually achieved. Never silently substitute a number               |

Two details a from-memory implementation gets wrong, both worth checking against
[xclim](https://xclim.readthedocs.io): rain is intercepted **per event, not per day** — the first
5.1 mm of a rain event never reaches the soil and a dry day restores the allowance, so 24 mm over
three days soaks in where the same 24 mm over eight separate days does not — and the drought factor's
numerator is `41x² + x`, **not** `41x² + 1`, which is about a whole unit in mid-range.

**A:**

---

**Q2. FFDI only, or GFDI as well?** The grassland index avoids the drought factor entirely — but it
needs curing and fuel load instead, and no open feed publishes curing for South Australia.
*Recommended:* **FFDI only.** Adding GFDI means adding the two inputs it needs, and that is the same
problem as Q1 wearing a different hat.
**A:**

---

**Q3. What are the three cache thresholds?** An answer is reused if it is close enough on all three.
*Recommended:* **20 km, 150 m, 30 minutes.** Thirty minutes because the models behind the forecast
only publish a new run every few hours, so anything shorter re-fetches the same numbers; 150 m
because a few hundred metres of elevation is a real temperature and wind difference in the Hills.
**A:**

---

<a id="q4"></a>**Q4. Where does the height come from?** The height threshold needs a ground elevation
per point, and Google's weather response does not carry one. *Recommended:* **Open-Meteo's elevation
endpoint** — free, no key, the same provider the rain history already comes from — cached permanently
per point, because terrain does not change. Google's Elevation API on the existing Maps key is the
fallback if it does not work out; I could not reach `api.open-meteo.com` from the sandbox to confirm
it, so treat this one as unverified.

**One trap:** the `elevation` field returned *inside* a weather response is the model grid cell's
smoothed height, which can sit hundreds of metres from the actual ground. For a cache threshold you
want real terrain, not the model's idea of it.
**A:**

---

**Q5. How far ahead?** *Recommended:* **72 hours hourly and 7 days daily.** The horizon does not
change the call count — one call either way — so it costs nothing to be useful.
**A:**

---

**Q6. Does the cache survive a restart?** *Recommended:* **yes, a SQLite file.** A few lines more than
a dictionary, and every cache miss is three billable calls against a monthly allowance.
**A:**

---

**Q7. What is the API?** *Recommended:* **two endpoints.** `GET /at?lat=&lon=` returning all four
blocks with the provenance of each, and `GET /health`. A key in a header. Nothing else until
something needs it.
**A:**

---

**Q8. Is it still called Anvil?** The name was chosen so that "the weather manager" always means the
Hub's and "Anvil" always means this. *Recommended:* **keep it** — it costs nothing and it still
solves the problem it was picked for.
**A:**
