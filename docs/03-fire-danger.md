# Fire danger: what the service computes, from what, and how far to trust it

Every fire danger figure the service gives, in one place: what it is, what it is computed from, where its
equations come from, how it was checked, and what it cannot tell you. The mechanism in full is in
[01-what-it-is.md](01-what-it-is.md); why each piece is the way it is, in [02-decisions.md](02-decisions.md).

## The short version

| Figure | Where you find it | Rests on | Verified against | Trust it for |
|---|---|---|---|---|
| **FFDI** (McArthur forest) | `fire.ffdi`, every forecast hour, each day's worst hour | temperature, humidity, wind, drought factor | Noble, Bary & Gill (1980), by hand | the classic forest index, forty years of records |
| **KBDI, drought factor** | `drought`, carried forward in the forecast | each station's own year of rain and heat | Keetch-Byram / Griffiths, tested | how dry the fuel is |
| **GFDI** (McArthur grass) | `fire.grass.gfdi`, hours, days | weather + the district's **curing** | Noble, Bary & Gill (1980), 9 reference tests | grass danger - **only where curing is entered** |
| **AFDRS grass FBI + rating** | `fire.grass.fbi`, `afdrsRating` | weather + curing + fuel load | AFDRS Grassland guide v2024.6.0, 10 reference tests | the public rating in grass - **only where curing is entered** |
| **AFDRS forest FBI + rating** | `fire.forest`, `forestFbi`, `forestFbiMax` | weather + drought factor + **provisional** fuel | AFDRS Forest guide v2024.6.0 and the official code | forest behaviour; the fuel is not the AFDRS's own default |
| **AFDRS rating here** | `afdrs`, each day's `pointFbiMax` | the land cover's fuel: forest or grass | DEA land cover, classes tested | the rating of the fuel actually on the ground |
| **CFS published rating** | `fireBan.today`, `fireBan.days` | what the CFS told the public | the CFS's own feed | **the official rating** - in season |
| **Wind change** | `forecast.windChanges` | the hourly forecast wind and temperature | synthetic cases | the hour a change arrives |

**The one rule:** the CFS's published rating (`fireBan`) is the official one. Everything else here is computed by this
service from published models - useful for the hours between, the places between, and the days the CFS does not rate -
and says what it rests on.

## Important points

1. **Out of season the CFS publishes nothing.** Its feed keeps the last day it did (4 May 2026, "No Rating"); every answer
   says "no rating for today" and when the last was, rather than showing May's as today's.
2. **Grass indices need curing, and nobody publishes it openly.** Enter each district's curing and fuel load weekly on the
   console's **Curing** page from the CFS's curing map. Without it there is no grass index and the answer says so. A
   figure older than a fortnight is still used, and marked old.
3. **The forest fuel is provisional.** The AFDRS forest equations are published and implemented exactly; its default fuel
   table is not (it lives in the Bureau's product IDZ10163, reached through the AFDRS Fuel State Editor, agencies only).
   The stand-in is the public reimplementation's dry forest set, long unburnt. Every forest answer says so.
4. **"AFDRS here" is only as good as the land cover.** DEA's land cover is 30 m, annual (2025 at the time of writing),
   and classifies what grows, not the AFDRS's 378 fuel types. Forest and grass are rated; water and built-up land are not
   burnable; wetlands, orchards and vineyards, and arid shrubland (chenopod, spinifex) are AFDRS fuels this service does
   **not** model - those places carry no rating and say why, never another fuel's figure.
5. **Every forecast index is per hour, on that hour's own weather.** A day's figure is its worst hour. The old way - the
   day's maximum temperature, least humidity and strongest wind put together - is an hour that rarely happens and reads
   the day worse than it is.
6. **The drought is carried forward.** Forecast rain and heat step the deficit and the drought factor day by day, turning
   at 9 am as the Bureau's day does, so a wet day ahead lowers the next day's index.
7. **A wind change is found, not forecast.** It is read off the model's hourly wind; the model's timing is the limit.
   Treat the hour as the model's best guess, and the fact of a change as the thing to act on.

## The models

### McArthur forest (FFDI)

`FFDI = 2.0 exp(−0.450 + 0.987 ln(DF) − 0.0345 RH + 0.0338 T + 0.0234 V)` - Noble, Bary & Gill (1980), with T in °C, RH
in %, V the 10 m mean wind in km/h (not the gust), DF the drought factor 0-10. Bands (pre-2022, the ones the index was
drawn against): low-moderate < 12, high < 25, very high < 50, severe < 75, extreme < 100, catastrophic.
Checked live by hand: 18.6 °C, 42 %, 4 km/h, DF 6.0 gives 3.61; the service said 3.6.

### Drought: KBDI and the drought factor

Each station's Keetch-Byram deficit is integrated from its own year of daily rain and maximum temperature (the Bureau's
days, the archive filling any gap), and the Griffiths drought factor drawn from it and the last twenty days' rain. In the
forecast the deficit is stepped through each forecast day's rain and heat, and the factor recomputed.

### AFDRS grass (and McArthur grass)

- **GFDI**, McArthur Mk5 grassland meter (Noble, Bary & Gill 1980): fuel moisture from T, RH and curing, the index from
  moisture, wind and fuel load.
- **AFDRS grassland**: the CSIRO grassland fire spread meter (Cheney, Gould & Catchpole 1998) with the Cruz et al. (2015)
  curing function, its intensity and the FBI - AFDRS Fire Behaviour Index Technical Guide - Grassland v2024.6.0.
  Grass condition (natural, grazed, eaten out) follows the fuel load, as the AFDRS does.

### AFDRS forest

The AFDRS forest model is the Dry Eucalypt Forest Fire Model (Cheney et al. 2012) - **not** Vesta Mk2, which the AFDRS
uses only for wet forest fuel availability. Implemented from the AFDRS Fire Behaviour Index Technical Guide - Forest
v2024.6.0 and checked line by line against the official AFDRS code (`fdrs_calcs.spread_models.dry_forest`, 2024.6.0):

- **Dead fuel moisture** by the hour and season: a sunny afternoon (October-March, 12-17 h)
  `2.76 + 0.124 RH − 0.0187 T`; night (19-06 h) `3.08 + 0.198 RH − 0.0483 T`; otherwise `3.60 + 0.169 RH − 0.0450 T`.
- **Fuel availability** `0.1 × DF`.
- **Spread**: 30 m/h × the moisture function below 5 km/h of fuel-level wind; above it
  `M × (30 + 1.5308 (U − 5)^0.8576 × FHS_s^0.9301 × (FHS_ns × H_ns)^0.6366 × 1.03)`, the moisture function
  `18.35 MC^−1.495` (floored at 4 %, 0.05 above 20 %).
- **Flame height** `0.0193 ROS^0.723 e^(0.64 H_el) × 1.07`; **fuel that burns**: surface (≤ 10 t/ha) and near-surface,
  elevated over 1 m of flame, half the canopy over two thirds of its height; **intensity** Byram's, 18,600 kJ/kg.
- **FBI**: intensity interpolated between 0, 100, 750, 4,000, 10,000, 30,000, 90,000 kW/m and 0, 6, 12, 24, 50, 100,
  200, floored. **Rating**: no rating < 12, moderate < 24, high < 50, extreme < 100, catastrophic.

Checked against the official code's own output rows: moisture 4.577 % and spread 56.65 m/h (10 °C), 3.8525 % and
69.29 m/h (25 °C), FBI 12, 19 and 6 for 916.8, 2,903.9 and 121.3 kW/m. Three details the public reimplementation gets
differently were settled by reading the official code: the accumulation since fire is not rounded, the canopy load is
held at its steady state, and each fuel layer has its own accumulation rate.

### AFDRS rating here: the fuel from the land cover

Digital Earth Australia's Landsat land cover, Collection 3 (FAO LCCS levels 3 and 4), read off its public map service
once per ~200 m and kept:

| Land cover | Fuel | Rated by |
|---|---|---|
| natural woody, cover 15 % or more | forest | AFDRS dry forest |
| natural woody under 15 %, natural herbaceous | grass (grassy woodland, grassland) | AFDRS grassland |
| cultivated herbaceous | grass (crop, pasture) | AFDRS grassland |
| cultivated woody | orchard, vineyard | not modelled here |
| natural aquatic vegetated | wetland | not modelled here |
| natural bare / sparsely vegetated | arid shrubland | not modelled here |
| artificial surface, water | not burnable | - |

### The wind change

At every hour of the next 48 the speed-weighted mean wind direction of the three hours before is set against that of
the hour and the two after. A swing of 45° or more with 15 km/h or more after it is a change; of a run of hours that
qualify, the change's hour is the one where the hours after have all come round and the hours before have not begun to.
A **cool change** cools 3 °C or more and comes into the south or west - the south-westerly change that turns a flank into
a head fire. Each change gives its hour, the directions and speeds either side, the gust after and how much it cools.

## On the map

- **Colour by** *FFDI today / tomorrow / day 3*, *AFDRS today / tomorrow / day 3* (each station's own fuel), and *Wind
  change* (hours until the next, soonest reddest). Choosing one fetches the forecasts missing or older than three hours,
  four at a time, while you look.
- **Fire ban districts** in the colour of the CFS's rating for today; **Warnings** shading fire weather districts and
  ringing stations in the public districts a warning names.
- A station's tooltip: its three FFDI peaks and its next wind change; its drawer: its fuel, the forecast with every
  index by the hour and each day's worst, and the CFS's days for its district.

## What would make it better

- **The AFDRS's own default fuel table** (IDZ10163), for the forest fuel - from the Bureau or an agency.
- **The AFDRS fuel type map** instead of the land cover: the 378 fuel types the official rating uses.
- **An open curing feed**, so the grass indices need no hand entry.
- **The wetland, horticulture, chenopod, spinifex and mallee models**, for the places now "not modelled".

## Sources

- AFDRS Fire Behaviour Index Technical Guides - Forest and Grassland, v2024.6.0, NSW RFS (June 2024), via AFAC:
  <https://www.afac.com.au/public-resources/afdrs--fire-behaviour-index-and-model-guides>
- The official AFDRS code, `fdrs_calcs` 2024.6.0, as published in the PyroXL repository (GPL-3):
  <https://github.com/Geoffysicist/PyroXL>
- Noble, Bary & Gill (1980), McArthur's fire danger meters expressed as equations. Cheney et al. (2012), Predicting fire
  behaviour in dry eucalypt forest. Cheney, Gould & Catchpole (1998), grassland fire spread.
- Digital Earth Australia land cover, Collection 3: <https://ows.dea.ga.gov.au/> (layer `ga_ls_landcover_c3`).
- CFS fire danger ratings and fire ban districts: CFS GeoHub (South Australia).
