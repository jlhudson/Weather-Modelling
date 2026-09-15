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

**Not in it:** drought integration, flood, rivers, Open-Meteo, MET Norway, the Bureau, satellites,
historical archives, forecast verification, a management page. Later, or never.

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

## The one real problem

McArthur Mk5, in the published form:

```
FFDI = 2.0 × exp( −0.450 + 0.987 ln(DF) − 0.0345 RH + 0.0338 T + 0.0234 V )
```

`T`, `RH` and `V` all come straight off Google. **`DF` — the drought factor, 0 to 10 — does not.**

It is not a reading of anything. It is a function of how much rain has fallen recently and how dry
the soil already was, which takes a rainfall history: about twenty days for the drought factor
itself, and roughly a year underneath it for the soil moisture deficit. Google's history endpoint
offers twenty-four hours.

So there is no way to compute a real drought factor from Google alone. **Q1 is what to do about
that**, and everything else on this page is detail by comparison.

---

## Questions

**Q1. Where does the drought factor come from?**

|   | Option                                                                     | Costs                                                                              |
|---|--------------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| 1 | **A configured constant**, returned as an input with its own provenance    | One line. The index is real arithmetic on an assumed input, and it says so           |
| 2 | **A number per month**, from a small table                                 | Slightly better, still assumed, and now there is a table to argue about              |
| 3 | **A number somebody maintains** — set on an endpoint, like a fuel figure   | Honest and current when kept up; silently wrong when forgotten                       |
| 4 | **Compute it** from a rainfall history                                     | The only true answer, and it needs a source that is not Google. This is the thing just cut |

*Recommended:* **option 1, built so option 4 can replace it without changing the answer's shape.**
`droughtFactor` appears in the response as a named input with `source: "configured"`, so the day it
starts being computed nothing downstream changes but the value and that one word.

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

**Q4. Where does the height come from?** The height threshold needs an elevation per point, and
Google's weather response does not carry the ground height. *Recommended:* **the Google Elevation
API**, on the Maps key that already exists, cached permanently per point — terrain does not change.
Alternative: skip height in the first version and compare on distance and time only.
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
