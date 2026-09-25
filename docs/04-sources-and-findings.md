# Sources and findings

Every outside source the service reads: what it takes, when, what it costs, how it fails, and what was learnt the
hard way building on it. The mechanism is [01-what-it-is.md](01-what-it-is.md), the decisions
[02-decisions.md](02-decisions.md), the fire danger figures [03-fire-danger.md](03-fire-danger.md). Findings are dated;
the sources change, so a finding is what was true that day.

## At a glance

| Source | What we take | When | Cost | Held |
|---|---|---|---|---|
| Bureau station file `IDS60920` | every SA station's latest observation | every 10 min, conditional GET | free | readings 3 days, windows and days 548 |
| Bureau warnings `IDZ00057` + products | SA warnings and the areas they cover | when asked, held 10 min | free | memory |
| CFS fire ban districts | the 15 districts' shapes | when asked, held a day | free (230 KB) | memory |
| CFS fire danger ratings | AFDRS rating, FBI, total fire ban, 4 days | when asked, held an hour | free (32 KB) | memory |
| Open-Meteo forecast | now, 72 h hourly, 7 days daily, 48 h behind | when asked, held 3 h (now: 1 h) | 3 units | `station_forecast`, a day |
| Open-Meteo archive / recent | daily rain and maximum for the drought | housekeeping and asks, missing days only | ~1 unit a fortnight of days | `station_day` |
| Open-Meteo flood (GloFAS) | river discharge, 92 days back, 7 ahead | when asked | 9 units to find a river, 8 a series | memory: river cell for life, series 12 h |
| Google Weather | the overflow forecast | only when Open-Meteo cannot | 3 units (1 per endpoint) | as a forecast |
| AWS Terrain Tiles | heights: terrain to 150 km, the coast | once a station; the coast once a process | free, ~100 tiles a station | `terrain` |
| DEA land cover (Collection 3) | the land cover class at a point | once per ~200 m cell | free | `land_cover` |

Every call is a row in `upstream_call` (the ledger); the free sources are rows at 0 units, so the Upstreams page shows
them with `?calls=all`.

## The Bureau

**Station file** - `https://reg.bom.gov.au/fwo/IDS60920.xml`, South Australia's whole state in one file.

- Read with a conditional GET. *Finding (W-21):* the ETag used to be kept even when the file failed to parse or be
  stored, so the next read heard "304 unchanged" and that file's readings were lost for good. A failed read now forgets
  the ETag, and memory follows only rows the database committed.
- *Finding (W-21):* some stations are tagged `tz="UTC"` (Thevenard, 018207). Taken at its word, Thevenard's Bureau day
  turned at 6:30 pm. Every SA station now keeps Adelaide's clock.
- *Finding (W-21):* where a station gives station-level pressure (`pres`) and no sea-level pressure (`msl_pres`), the old
  parser stored `pres` as sea level and blended it with true sea-level pressures - about 0.12 hPa wrong per metre of
  height. Only `msl_pres` is pressure now.
- *Finding (W-21):* the running maximum (`maximum_air_temperature`) runs 6 am to 9 pm local; the day's maximum now takes
  it as it stood at 9 pm, not the next morning's three hours.
- *Finding (23 September 2026):* the file can go quiet. It did for about two hours in the evening; every station went
  stale and the map showed nothing. Now a quiet station keeps its last reading faded (W-28), a reading asks the model for
  its now (W-20), and the side panel says since when the file has not changed.

**Warnings** - the listing `https://reg.bom.gov.au/fwo/IDZ00057.warnings_sa.xml` and a product XML per item
(`https://reg.bom.gov.au/fwo/IDSnnnnn.xml`).

- A product names every area it covers: public forecast districts (`SA_PWnnn`), fire weather districts (`SA_FWnnn`),
  and river basins for floods. *Finding (W-25):* the pre-start-over parser kept only public districts, so fire weather
  and flood warnings matched nothing and vanished. Now every area is kept, and a warning that matches nothing at a place
  is still listed as elsewhere in the state.
- *Finding (25 September 2026):* the marine and surf summaries link to pages, not products, and carry no areas; when a
  warning is not current its product URL returns 404. There were no warnings in force anywhere in Australia that day, so
  the parser was tested on a real Tasmanian severe weather warning kept from before the start-over.
- The Bureau does not publish its public forecast districts as open shapes, so on the map a public-district warning
  rings the stations in it; a fire weather district is a CFS district (the CFS feed carries its `SA_FW` code), and is
  shaded.

## The CFS

**Fire ban districts** - `https://cfs-feeds.geohub.sa.gov.au/FL/CFS_Custodial_Read/CFS_Fire_Ban_Districts/FeatureServer/0/query`.

- *Finding:* this "feeds" URL is a fixed file (230 KB, Web Mercator) that ignores query parameters.
- *Finding:* its rings are Esri polygons - outer rings clockwise, holes anticlockwise - so a district cut out of another
  is a hole, and is read as one (W-23).
- *Finding:* it writes district names in capitals ("ADELAIDE METROPOLITAN"); the ratings feed writes them in title case.
  Names are matched without regard to case and shown as the ratings feed writes them.

**Fire danger ratings** - the CFS GeoHub's `South_Australia_Fire_Danger_Ratings_Read` layer, without geometry.

- Today and the next four days per district: `firedangerrating_n`, `fbin`, `tfbn`, with local and UTC start and end
  times; `aac` is the district's Bureau fire weather code.
- *Finding (25 September 2026):* out of the fire danger season the CFS stops publishing and the feed keeps the last day
  it did - "No Rating", for 1 to 4 May 2026. Every day carries its date; a day before today is never given as today's.
- *Finding:* the feed repeats today as day 0 and day 1; a date is kept once.

## Open-Meteo

The primary forecast, the archive the drought is filled from, and GloFAS. Free: 600 calls a minute, 5,000 an hour, 10,000
a day, 300,000 a month, in weighted units - a location's every fortnight of days, or every ten variables, is a call. The
service retires it at 90 % of each window and the breaker opens on the window its refusal names.

- *Finding (W-21):* hourly stamps are local hours (`timezone=auto`), which in Adelaide fall on the half hour in UTC.
  Cutting "the hours from now" at the UTC hour dropped the hour now running for half of every hour. The hour is kept
  while it lasts.
- *Finding (W-2):* the elevation endpoint counts every point as a call - six calls of a hundred points met the minute's
  limit. The terrain comes from the AWS tiles instead.
- *Finding (W-26):* **GloFAS's nearest cell is often beside the river, not on it.** At Renmark the nearest 0.05° cell
  reads 0.00 m³/s and the Murray - 276 m³/s that day - is the next cell west. The river is the largest flow in the 3×3
  block around a point, found in one multi-location call and remembered. Cell centres are on the quarter-hundredths
  (-34.175, 140.775), and a point is snapped to them before the block is laid out.
- The flood API is counted like the rest: nine locations for one day each is nine units; one location's 92 days back and
  seven ahead is eight.

## Google Weather

The overflow: used only when Open-Meteo is held or failing. *Finding (W-21):* its free allowance is 10,000 a month on
each of its three endpoints (current, hourly, daily), and one fetch is one call on each; it was counted against a single
10,000, and so would have been retired at a third of what it is allowed. It is 30,000 units now.

## AWS Terrain Tiles and the coast

`https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png` - terrarium PNGs, no key, no published limit.

- Zoom 10 (~125 m a pixel here) for the terrain; the sea is negative (bathymetry), Lake Eyre about −15.
- *Finding (W-19):* sampling to 150 km is about a hundred tiles a station (~4 MB) and a full resample of 82 stations took
  about fifteen minutes.
- The coast is found once a process in 64 zoom-7 tiles (about a kilometre a pixel) as the water joined to the ocean -
  flood-filled from the Southern Ocean - so the gulfs are sea and Lake Eyre and the salt lakes are not. 8,528 pixels of
  coastline on 24 September 2026. Distances from the sea that day: Woomera 182 km, Coober Pedy 357, Oodnadatta 535,
  Moomba 547, Pukatja 588.

## Digital Earth Australia land cover

`https://ows.dea.ga.gov.au/`, layer `ga_ls_landcover_c3` (Landsat, Collection 3), read with a WMS GetFeatureInfo as JSON
- one pixel, every year it holds.

- *Finding (25 September 2026):* Collection 3 runs 1988 to 2025; the older `ga_ls_landcover` stops at 2020.
- Classes are the FAO LCCS: level 3 (111 cultivated terrestrial vegetated, 112 natural terrestrial vegetated, 124 natural
  aquatic vegetated, 215 artificial surface, 216 natural bare surface, 220 water) and level 4, whose label carries woody
  or herbaceous and the cover band ("Woody Open (40 to 65 %)").
- *Finding:* at 30 m the country is a patchwork. A point in the Adelaide Hills probed first read woodland; the same point
  on its ~200 m cell read a pasture paddock. The class is the pixel's, not the district's.

## AFDRS

- *Finding (W-33):* the AFDRS forest model is the Dry Eucalypt Forest Fire Model (Cheney et al. 2012), not Vesta Mk2; Mk2
  appears only in the wet forest's fuel availability.
- *Finding (W-38):* the official code, `fdrs_calcs` 2024.6.0, is public inside the PyroXL repository as a wheel. Read line
  by line, it differs from the PyroXL reimplementation in three places: the accumulation since fire is not rounded, the
  canopy load is held at its steady state, and each fuel layer has its own accumulation rate. The service follows the
  official code.
- *Finding (W-38):* the default fuel parameters are **not** in the code. It reads them from the Bureau's product
  `IDZ10163_AUS_FSE_fuel_type_table_SFC.csv`, which is not on the Bureau's public web or FTP and belongs to the AFDRS Fuel
  State Editor (<https://www.fse.afdrs.org.au/>), open to agencies only. The forest fuel stays provisional until it can be
  had.
- Grass curing is published by the CFS as a weekly map, not as data; there is no open feed. It is entered on the Curing
  page.

## Budget: what the asks cost

| Ask | Open-Meteo units |
|---|---|
| a reading where the stations are fresh and the forecast is held | 0 |
| a reading that fetches the nearest station's forecast | 3 |
| a quiet station's model now, per station in reach | 3 (then held an hour) |
| a point of ours dropped | 3 + its year of archive (~26) |
| the river at a new place / its series | 9 / 8 (series held 12 h) |
| colouring the map by an outlook, first view | up to ~250 (82 stations × 3), then only what ages past 3 h |
| a year of archive for one station | ~26 |

The daily allowance is 10,000; the 90 % guard retires Open-Meteo at 9,000 and Google takes the overflow.

## Lessons

- **Out of season is not "no danger".** The CFS stops publishing, the curing map stops; the service says there is nothing
  for today rather than carrying May forward.
- **Nearest is not the same as right.** The nearest GloFAS cell is beside the river; the nearest station's forecast is
  the forecast's, not the point's; the land cover is the pixel's. Each is said where it is used.
- **An index from a combination that never happened overstates the day.** Per-hour indices, and the day's worst hour.
- **Say what is missing.** No curing: no grass index, and why. A fuel not modelled: no rating, and why. Provisional
  fuel: said on every answer. A rating not published: said, with the last date.
- **A test on a real file beats a test on a guess.** The CFS files, a Bureau warning and the station file are fixtures;
  the AFDRS forest model is checked against the official code's own output rows.
- **Work that runs on a clock is work that runs when nobody asked.** Everything but the Bureau's file and the daily
  housekeeping is fetched when asked and held for a stated life (W-15).

## Working on it

- The map is looked at offline in a harness: the live feeds saved as files beside the real `map.js` and CSS, a `fetch`
  shim mapping the console routes to them, served by the JDK's `jwebserver` on port 8099. The browser pane reports a zero-size map while hidden - bring it to the front
  before counting what is "in view" - and its clock may not be the machine's.
- The console code is never typed into the login form; the console's JSON is read through the API with a key.
- Other sessions commit to this repository too; W-numbers are taken from `git log` at commit time, and only this work's
  files are staged. `src/test/resources/contract/consumers.json` names the fields The Hub and IncidentWatch read directly; renaming or
  dropping one fails the tests on purpose.
