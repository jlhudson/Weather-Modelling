# Decisions

One entry per decision, numbered W-1 onward from the start-over of 21 September 2026. The commit
that made each carries its number. Earlier decisions are in the git history before that date and
are not carried forward: the service they describe was deleted whole.

### W-1 · Start over: stations, South Australia, no hexagons

**The decision.** Everything the service had accumulated — the 17 km hexagons and their store, the
drought and its archive, the fire indices, the CFS feeds, the warnings, the wind-change and diurnal
derivations, the terrain rasters, the readings API and its contract, the history, eleven migrations
and six documents — is deleted, along with the data: the Postgres volume is dropped and the schema
starts again at a new `V1` (the Hub's API key is carried across so nothing has to be re-issued).
What stays is the platform (login, keys, diagnostics, the ledger), the two forecast clients and
their allowance page, and the Bureau's station file — for South Australia only, on a ten-minute
timer, every station in it held with its latest values in memory. The map draws the stations and
opens one on a click. The git log is kept; the decision numbering restarts.

**What it costs.** The Hub's `/api/weather` answers 404 until a point can be answered from the
stations again, which is after the reach (W-2) and the interpolation between overlapping reaches.

**Why.** The hexagons were the wrong unit: one 17 km cell over Adelaide, the Hills and the plains
towards Murray Bridge carried one answer for three climates, and every layer on top — the blend, the
drift, the drought's rings — was a way of arguing with that. The stations are the facts; what each
one speaks for should be a shape drawn from the ground, not a tiling drawn from arithmetic. Starting
from the stations, with nothing derived until the shape is right, is cheaper than unpicking. — James,
21 September 2026.

### W-2 · The reach: a polygon per station, drawn once from the terrain by a cost rule

**The decision.** Every station carries a reach — the ground it speaks for — as a polygon. The
terrain around each station is sampled once from the Terrain Tiles on AWS's open data registry
(the station and every kilometre out to 50 km on 48 bearings, 2,401 points off some fifteen tiles) by
a background job that takes one station every fifteen seconds, and kept. Open-Meteo's elevation
endpoint was the first choice and lasted six calls: it counts every point, so a station is 2,401
against a day's 10,000. The polygon is arithmetic over those samples under a rule
of two numbers, both sliders on the map: the *reach* (40 km) and *what a hundred metres of height
costs* of it (10 km). A ray stops where its distance plus the cost of the greatest height difference
it has crossed exceeds the reach — the greatest, so a ridge is a barrier — with a 3 km floor. The
sliders preview any rule on every station at once; *set* makes it the rule and a restart keeps it.
The clicked station's reach is drawn in cyan, every station's on a toggle; the drawer carries the
area, the range of the rays, why each stopped (as a rose), the model's height against the Bureau's,
and a *sample it now* for a station the job has not reached. `/api/v1/reach.geojson` is the same
collection for a caller.

**Why.** Distance alone made one answer for Adelaide, the Hills and the plains beyond; a hard height
cutoff would make a station count or not with nothing between. A cost trades the two smoothly, and
taking the greatest difference crossed rather than the difference at the point is what keeps the
far side of a ridge out. Sampling once and drawing on demand is what makes the sliders free to turn
and honest — the shape only ever changes when the rule does. Temperature and humidity are kept out
of the shape on purpose: they are what the polygon carries, and they belong in the interpolation
between overlapping reaches (with the lapse rate) and as a check on the rule, not in its geometry.
— James, 21 September 2026.

### W-3 · The ocean: a ray ends at the water, a coastal station is held to a limit

**The decision.** A ray ends at the water — the first terrain sample at or below sea level stops it
half a step short, so the shore is inside the reach and the sea is not. A station with water inside
10 km on any bearing is coastal, and every one of its rays is held to a third number on the rule, the
coastal limit, 25 km by default and a third slider beside the other two. Land below sea level counts
as water. The drawer says whether a station is coastal and how near the water is, the rose colours
the rays the water and the limit stopped, and a coastal station wears a thin blue ring on the map.

**Why.** A reach that ran out over Gulf St Vincent answered for water nobody stands on and, worse,
for the far shore. Maritime air is its own climate — a coastal station's afternoon is the sea
breeze's, cooler and damper than twenty kilometres inland — so a coastal station should reach along
the coast as far as any, and inland only as far as its air does; twenty-five kilometres is roughly
where a sea breeze gives out, and it is a slider because that is a guess to be looked at. Whether the
inland side should shorten smoothly rather than at a wall is the next thing to see on the shapes. —
James, 21 September 2026.

### W-4 · Rivers are not water: water is what is three kilometres across

**The decision.** A ray ends at water only where the water is at least 3 km across along the ray —
three consecutive samples at or below sea level. A river is a line and never is: the lower Murray,
which the tiles read at sea level below Lock 1, is crossed like any dip in the ground (its bed still
costs what its depth costs). The sea and the big lakes are areas and always are, so Murray Bridge and
Strathalbyn still end at Lake Alexandrina. *Coastal* is such water inside 10 km. One constant, no
slider.

**Why.** Pallamana was coastal to the Murray six kilometres away, every ray capped at 25 km, and
James asked for rivers to be ignored: a river has little or no effect on the weather beside it, while
the gulf plainly does. Width is the honest difference between the two, and three samples is the
narrowest the 125 m tiles can be trusted to tell. Telling a lake from the sea by depth is the next
refinement if a lake-side station ever reads as coastal wrongly; no South Australian station does
today. — James, 21 September 2026.

### W-5 · The probe: which stations speak for a point, laid out and not blended

**The decision.** A click anywhere on the map, and `/api/v1/stations/at?lat=&lon=` with a key,
answers with the stations whose reach contains the point — nearest first, each with its distance,
bearing, height above or below the point, what it last said and how old that is, and how far past
the point its ray goes — and the nearest three whose reach does not, with why their ray towards the
point stopped short. On the map: a crosshair at the point, cyan spokes to the stations in reach and
their reaches faintly, grey dashed spokes to the three outside. The point's own height is one read of
the elevation tiles, which are already cached. Nothing is interpolated.

**Why.** James wanted to see the stations within range of a point before any blending exists — the
ingredients before the recipe — so the membership rule can be judged on its own, and an omission can
be seen rather than wondered about. The endpoint is the one a reading at a point will grow on, and
the one the Hub's `/api/weather` can be pointed at when it does. The five ways to blend them — nearest
station, inverse distance, height-corrected inverse distance, Barnes successive correction, kriging
with an elevation drift — were laid out on 21 September and none chosen yet. — James, 21 September 2026.

### W-6 · The record, and the drought from it: six-hour windows, Bureau days, a year backfilled

**The decision.** A real station's readings are kept as six-hour windows (3, 9, 15, 21 local) and
as Bureau days — the 24 hours from 9 am, rain the published total to the next 9 am, maximum the
highest reading between — both 548 days. The days a station's year lacks are filled from
Open-Meteo's reanalysis archive by a background job, one station every fifteen seconds, only the
missing days, never over a day the station made itself. Each station's drought is the Keetch–Byram
deficit integrated from its own record — from field capacity at the start of the year behind
today, the mean annual rainfall from that year — and the Griffiths drought factor from the deficit
and the last twenty days of rain; both on demand, memoised, on the stations feed and the detail. The
formulas are the ones this service had before the start-over, restored from git with their tests
(Keetch & Byram 1968 in Crane's metric form; Griffiths 1999 as corrected by Finkele 2006), not
retyped. The drought rides the same reach as the current: one polygon per station, one membership.

**Why.** The deficit is a running total, not an observation: it has to be integrated from history,
and a year is the honest spin-up because a South Australian year always holds the wet season that
resets it. Interpolating each station's *index* rather than its inputs is what the Bureau's gridded
drought factor does and keeps the record where it belongs, with the station. Backfilling only the
missing days, and resting a station six hours between attempts, is what keeps a hundred stations at
a few hundred units a day at worst and nothing at best. A day without both a rain total and a
maximum is left for the archive rather than written half-known. — James, 21 September 2026.

### W-7 · The points of our own: a place nobody reaches becomes a station

**The decision.** A place no Bureau station's reach contains - or that only stations without a
temperature reach - is dropped as a station of the kind `point`: the same terrain, reach and rule,
its current from Open-Meteo (one hour's life), a year of record from the archive. A later ask inside
its reach reuses it and fills only what is missing; one unasked for 548 days is dropped again. It
has no six-hour ledger. On the map it is an amber diamond, never a dot.

**Why.** James asked that a point outside every station become a drought station of ours, updated
only when the next ask inside its polygon comes, its days backfilled rather than kept current. Making
it a station of a second kind, rather than a second machinery, is what lets the reach, the record,
the drought and the map treat it as one thing with one exception - where its current comes from. —
James, 22 September 2026.

### W-8 · The reading: height-corrected inverse-distance weighting, per value, over one polygon

**The decision.** A reading at a point blends the stations whose reach contains it: weights
`1 / cost²` with the cost measured along the ray as the reach is; temperature, dew point and the
day's maximum brought to the point's height by the lapse rate first; humidity, wind, rain and each
station's own KBDI and drought factor blended as they are; the wind's direction as a vector. A
station lacking a value stays out of that value's blend. The FFDI is computed from the blend and is
null when any input is missing. A click on the map asks for the reading; `/api/v1/reading` answers
a caller. Every value names the stations behind it.

**Why.** James chose height-corrected IDW from the five laid out on 21 September, and asked that the
drought use the same polygon as the current and that a station without the right values be left out
rather than guessed for. Taking the cost along the ray, rather than the bare distance, is what keeps
a station across a ridge from counting as much as one across the plain, and it costs nothing - the
terrain is already there. — James, 22 September 2026.

### W-9 · The wind as a trend at every station, and the whole of the file in the drawer

**The decision.** Every station carries, beside its latest wind, the mean of its newest five
readings — speed and gust as means, direction as a vector — with how many and over how long. The
map draws both from zoom 8 (the latest solid, the mean grey), the tooltip says both, and the drawer
lists them with the rest of what the file carries: pressure, dew point, apparent temperature,
visibility, cloud and oktas, delta-T, the day's maximum and minimum.

**Why.** James asked to see the wind now and a five-reading average at every station, and the
pressure and the other Bureau fields when a station is clicked. Five readings is fifty minutes of a
ten-minute file: long enough that a gust is not a change, short enough that a change is not lost.
— James, 22 September 2026.

### W-10 · No ocean borders: water ends no ray, the sea is sea level for the cost

**The decision.** A ray no longer ends at the water. The sea is ground at sea level for the height
cost and nothing more, so a station on an island or a headland reaches across the water to the far
shore as it would across a plain, and the small islands whose station sits a few hundred metres out
in the sea have weather again. Water is still seen: what is at least 3 km across along a ray (W-4)
inside 10 km of the station makes it *coastal*, and a coastal station is still held to the rule's
coastal limit on every bearing — the three sliders stay as they are. The rose has one colour fewer;
the drawer says the water is crossed, not a border.

**Why.** James: "there are a lot of small islands that have no weather due to simply having their
station in the ocean by a few hundred meters. so for now, NO ocean borders please." A border at the
sea was drawn to keep a reach off the water nobody stands on, but its cost fell on the stations
that matter most to the coast — a station in the sea reached nothing, and a headland's reached only
along its own spit. Overlap is allowed and the interpolation weighs by cost, so a reach that runs
out over the gulf does little harm: the far shore is 1/cost² away, and its own stations outweigh it.
The coastal limit does the work the border did, and it is a slider. — James, 22 September 2026.

### W-11 · Pressure as a colour; the direction comes with the wind, not with a button

**The decision.** *Pressure* (mean sea level, hPa, 990 to 1040) joins the colours a station can be
coloured by, and the tooltip says it. The *Wind* toggle is gone: choosing *Wind* or *Gust* as the
colour draws every reporting station's direction at every zoom - the latest as a solid arrow the
way the wind blows, its length the speed coloured by - and from zoom 8 the mean of the last five
behind it in grey, as before. Any other colour draws no arrows.

**Why.** James asked for pressure as a selectable colour, and for the direction to come with the
wind when it is chosen - "I don't need an additional wind button." A separate switch made the
arrows a second thing to remember, and drew them over a map coloured by something else; the
direction is part of what *wind* means, so it belongs to the chip. The mean arrow still waits for
zoom 8 because two arrows a station at the whole-state zoom is a thicket. — James, 22 September 2026.

### W-12 · The ocean border is back, except for an island: a station the water would take three quarters of

**The decision.** A ray ends at the water again, as W-3 had it, and a coastal station is held to
its limit as before. But a station the water would end at least three quarters of the rays of - 36
of 48 - is an *island*, and for it the water ends none: the sea is ground at sea level for the
height cost and nothing more, and the station reaches across it to the shore beyond. It is still
coastal, still held to the coastal limit. The share is one constant, `ISLAND_SHARE`, not a slider.
The drawer says how many rays the water ends, or for an island would have, and calls the station
an island; on the map an island's coastal ring is dashed. W-10 is undone but for the clamp it
introduced, which the island rule needs.

**Why.** James took W-10 back: he wants the ocean border on the polygons - a shore station should
not answer for the far side of a gulf - but not for the little islands, whose station a few
hundred metres out to sea had no reach at all. The two cases differ in how much of the station the
water would take: a shore takes half a station's rays, a headland two thirds, a jetty or an islet
nearly all. Three quarters is the line James named; on the South Australian stations it takes in
the two that stand in the sea - Neptune Island (48 of 48) and Black Pole (44) - and six headlands,
Cape Willoughby (44), Stenhouse Bay (42), Robe (42), Point Avoid (39), Thevenard and Warburto Point
(36 each), and leaves Edithburgh and Cape Borda (34) their borders. It is a constant, not a slider,
because the honest fix for a station on the wrong side of it is to look at that station, not to move
every one. — James, 22 September 2026.

### W-13 · A force grab on the reading: the upstreams asked first, whatever the timers say

**The decision.** The reading's drawer has a *force grab* pill, and the reading route takes
`force=true`. A forced ask reads the Bureau's file now, fills every day each station in reach is
missing (any missing day, rest or no rest), and fetches a point of ours' current again however young
it is - then gives the same reading as ever, with a `grabbed` block saying what came: a new Bureau
file or the same one, the model's current or not, how many days of record. The drawer says it in a
line under the reading's source. A forced ask inside stations' reach makes no model call and drops
no point.

**Why.** James asked for "a little force grab button to force grab the weather & drought at that
point" - a way past the timers when he is looking at a place now: the Bureau's ten minutes, the
hour a point's current is kept, the backfill's six-hour rest and three-day tolerance. Asked what a
force should do where stations reach, he chose the Bureau re-read over asking the model at the
exact point: the blend is the reading there, and a point dropped inside a reach would be a second
answer to argue with. — James, 22 September 2026.

### W-14 · The ask, to the letter: points only on request, a droughtless member filled, the click through the API

**The decision.** Three things, so the code does what the spec says. A point of ours is never
touched by a timer: the backfill's tick walks the Bureau's stations only, and a point's current and
missing days are fetched when an ask lands in its reach, and only then. An ordinary ask fills a
member station whose record is too short for a drought, then and there (rested six hours between
tries per station), so the next ask has it. And a click on the map is an ask from outside: it goes
to `/api/v1/reading` and `/api/v1/stations/at` through the API's front door with the console's own
key - issued to the consumer `console` with the readings scope, carried on the map page, listed on
the API keys page like any other and issued again if revoked. The console's twin routes for the
reading and the probe are gone. The console key is the one whose plaintext the service keeps, in
the setting table, because the page has to carry it.

**Why.** James set out the flow - inside a polygon, IDW over the stations, a station without
drought grabbed; outside, a point of ours with its NOW and its drought, reused by later asks in
its polygon, its NOW only ever fetched on request - and asked for it to be confirmed. Two things
differed: a point's record was topped up by the timer, and a member without drought was left out
rather than fetched. He chose both fixes, and added that the click is meant to represent an API
call from outside, so it should be one, to keep the process flow honest. — James, 22 September 2026.

### W-15 · Two timers and the database as the store: the readings kept, the history folded once a day, nothing else on a clock

**The decision.** The service runs two timers and no more. Every ten minutes it reads the Bureau's
file and writes every new reading to `station_reading`, as published; what stays in memory is the
one cache kept - each station's latest and newest six, read back from the table at the start. Once
a day at 9:30 local (and once, a minute after the start) the housekeeping runs: it folds each
Bureau station's stored readings of the last three days lacking a row of the station's own into the
four six-hour windows and the Bureau day; prunes readings older than three days, windows and days
older than 548, the upstream ledger and the points of ours unasked for 548 days; samples the terrain
of any station lacking it; and fills the year of any Bureau station missing days, one after
another, as far as the day's allowance allows. The fifteen-second terrain and backfill ticks and the
hourly sweep are gone, and so is the drought memo: a station's drought is a millisecond of
arithmetic over its days, done when asked. Everything else is on demand.

**Why.** James: "The Weather application only needs to grab the 10 minute data, and the cleanup /
history (perhaps daily?) - those are the only two automated tasks, the rest is on demand ... The
application should be storing data into the database, and cleaning up / creating 6 hourly history
once a day ... no cache or a basic cache for just the recent calls." Before this the readings were
never stored - each was folded into an open window in memory and a restart lost up to six hours of
it - and three tickers ran whether or not anything needed doing. Now the store is the database, a
restart loses nothing, the history is rebuilt from what was stored rather than from what happened
to be in memory, and the service idles at the cost of one small conditional GET every ten minutes.
Three days of raw readings is enough to fold a day the housekeeping missed; the windows and the days
are the history. One tightening came with the fold: a day is the station's own only when every one
of its four windows has a reading, because a maximum taken from part of a day - the service started
at dusk - is not the day's, and the archive's is. — James, 22 September 2026.

### W-16 · A barrier has to hold, and climbing costs more than descending

**The decision.** Two changes to what the ground costs a ray. First, ground is a barrier only where
it keeps its height for three samples - 3 km, the same rule the water has (W-4): a gully one or two
kilometres across is crossed for nothing, and a barrier costs from the step it begins at, so a ray
stops at the foot of a wall rather than two kilometres into it. Second, climbing and descending are
counted apart and added: the slider is the price of a hundred metres of climb, and a hundred metres
of descent costs a share of it - half by default, on a fourth slider, 0 to 100 %. The reach's
arithmetic now lives in one place, `Reach.costKm`, which the interpolation's weights use too, so the
polygon and the blend cannot disagree.

**Why.** James: "If I set the height factor and the distance for polygons, I either end up with
Adelaide spreading way out into the hills, or I end up with the hills representing practically no
land. There is no in-between." He was right, and the cause was two different things sharing one
number. A plains station has one steep direction, so the factor that stops Adelaide at the scarp is
the factor that kills a hill station, whose ground falls away on every bearing; and the Mount Lofty
ridge is dissected, so a ray along the ridge top fell into a one-kilometre valley, locked in the
greatest difference, and died although the ground came back up to the station's own height. The
persistence rule fixes the ridge, the asymmetry fixes the hill station, and the two sliders are now
independent: raise the climb cost to hold Adelaide back and the hills keep their ground. Of the five
options put to him James chose these two together. At 35 km, 5 km per 100 m and half, West Terrace
reaches 13 km east and 30 km north, Mount Lofty 17 to 27 km, Mount Barker 22 to 31 km.
— James, 22 September 2026.

### W-17 · The legend's distribution back, and a bar lights its stations

**The decision.** The legend at the foot of the side panel carries the stations' distribution again:
twenty-four bars over the colour's range, each in its colour on the ramp, above the ramp and the
min, mean and max. A switch beside the count - *in view* by default - counts only the stations
inside the map view, recounted on every pan and zoom; off, every station held. Hovering a bar lights
the stations whose value falls in it - a cyan ring each, the rest faded to ghosts, and with All
reaches on their reaches firm and the others faint - and the count says how many and the bin's
range. Hovering a station, or its reach, lights its bar.

**Why.** James: "Previously we had a histogram in the bottom left, allowing me to see all or only all
in view. I would like this back please. It also allowed me to hover over the bars and have them
highlighted on the mapping screen." The histogram and its switch went with the start-over (W-1);
before it a hexagon lit its bar but a bar lit nothing, so the map-from-the-bar direction is new.
— James, 23 September 2026.

### W-18 · An admin page that deletes all the data and starts again, for testing

**The decision.** A console page, Admin, with the row counts of the weather's tables and one button.
It empties `station_reading`, `station_hour6`, `station_day`, `terrain` and `station` - the Bureau's
stations and the points of ours alike, the rain, the history, the drought's year - reads the memory
back from the empty tables, forgets the Bureau file's validators and the terrain tile cache, reads
the Bureau's file whole at once, and starts the housekeeping in the background, which samples every
station's terrain and fills the year of record as far as the day's allowance goes. The Bureau's
timer and the housekeeping are held off while the tables are emptied. Kept: the console login, the
API keys, the reach rule, the upstream ledger - so the allowances still know what today has spent -
the diagnostics and the access log.

**Why.** James: "I need a admin page that will allow me to DELETE ALL THE CURRENT DATA! For testing
purpose only ... it should delete all data, regrab the BOM files and commence operation. Yes, we loose
all rain, those extra locations and more. This is intentional, as we should have all data regrabbing
setup. I also expect history to be removed." The ledger is kept deliberately: wiping it would let a
reset spend Open-Meteo's daily allowance twice.
— James, 23 September 2026.

### W-19 · No coastal limit; the further inland, the further a station reaches

**The decision.** The coastal limit is gone - the slider, the cap it put on a station with water
inside ten kilometres, and the blue ring; the sea still ends a ray at the shore, and an island still
reaches across it. In its place a station's reach grows with its distance from the sea: the rule's
reach, and a share of it again for every hundred kilometres inland, 20 % by default on a slider from
0 to 50, to at most 150 km - `reach × (1 + share × inland ÷ 100)`. At 40 km a coastal station
reaches 40 and one 300 km inland 64. To let it, the terrain is sampled to 150 km instead of 50
(7,201 points, about a hundred tiles a station; a station sampled to 50 is read as unsampled and the
housekeeping samples it again). The distance is to the sea: found once, in 64 zoom-7 tiles over the
state and the ocean below it, as the water joined to the ocean - the gulfs are sea, Lake Eyre and
the salt lakes are not - and kept with the terrain.

**Why.** James: "Lets remove the Stations that are ocean / near to ocean, these have a 'Max distance
from the ocean' scale - Remove it. Lets add a scale. As in, the further inland a point is, the bigger
is affective radius ... so the super regional stations have a bigger area, but allow me to use a
scale to refine it." Of the options put to him he chose the limit and its ring to go and the sea's
border to stay, the distance measured to the sea only, and a linear share to 150 km. The outback's
stations are hundreds of kilometres apart; a reach that is right for the Adelaide plains leaves most
of the state spoken for by nobody.
— James, 23 September 2026.

### W-20 · Forecasts, and the model's now when the Bureau goes quiet

**The decision.** A reading carries the next twelve hours and three days: the forecast of the nearest
station whose reach contains the point, or of the point of ours where none does. One forecast a
station, kept in `station_forecast` and fetched again by the next ask that finds it older than three
hours; nothing fetches on a clock. A station in reach whose file has gone quiet - its latest older than
seventy minutes - has the model's now fetched for it, which stands in for its reading in the blend and
on the map, labelled the model's, and is never written into its readings or its history. The station
drawer shows its forecast too, and the admin reset empties the table with the rest.

**Why.** James: "Lets now add in openmeteo forecasts please. If it's overlapping a station then we use
the NEAREST station's forecast. If it's not near then we create a temporary one. Forecasts are for hourly
updates for 12 hours plus a 3 day update. Once it's older than 3 hours, if a new request for that
station comes in, then we need to refresh. If the now files are down we should manually do a NOW update
for all overlapping stations at that point to somewhat force an update." He chose: overlapping means
inside a reach; the model's now in the reading and on the map but never in the history; the forecast in
the reading, the station's drawer and the API; kept in the database. The evening the Bureau's file
stopped for two hours, every station went hollow and the map said nothing - this is the answer to that.
— James, 23 September 2026.

### W-21 · A round of bugs and dead code

**The decision.** A review of the whole service, and every finding fixed. The ones that change what it
does:

- *The console's lockout held.* An attempt is now counted, atomically, before the code is checked, and
  the login is no longer transactional - a refusal rolled its own count back, and parallel guesses all
  read the same count. The default code `12345678` is gone: without `WEATHER_CONSOLE_CODE` the stack
  will not start.
- *An unknown API scope reads nothing* (it read everything), and the console refuses to issue one.
- *The access log is bounded*: 20,000 rows waiting at most, the rest counted and dropped; written in
  full batches; pruned after thirty days by the housekeeping.
- *Diagnostics' "clear what is shown" clears what is shown* - the page's window, not the week.
- *The Bureau's file*: a station tagged `UTC` keeps the state's clock (Thevenard's day turned at
  6:30 pm); station-level pressure is no longer taken for sea-level; a file that fails to be taken in is
  downloaded whole on the next read rather than answered "unchanged", and memory follows the table only
  once the rows are committed.
- *The day's maximum* takes the Bureau's running maximum as it stood at 9 pm (6 am to 9 pm), not the
  next morning's three hours.
- *The backfill asks for the missing days only*, in runs a fortnight apart at most, not the whole span
  between the first and the last; and the admin reset forgets each station's rest, so its housekeeping
  fills every year at once.
- *Forecasts*: the hour now running is kept (Adelaide's hours fall on the half hour in UTC), and a forced
  ask never fetches the same forecast twice. The API's station detail carries the forecast as the map's
  does - one service builds both.
- *Google's allowance* is 10,000 a month per endpoint, 30,000 units across its three.
- *The ledger's memo* no longer grows a key a minute for the life of the process.
- *`waterKm`* is the nearest water within the terrain on any bearing, not only where a ray got that far.
- *The map*: an answer to an earlier click is dropped rather than drawn over a later one; an error
  response is never taken for data; the reaches sit in a pane under the stations, so a click on a dot
  hits the dot; sampling a station no longer pulls the drawer back to it; the probe's spokes follow the
  theme.

The dead code went with it: the pre-W-19 coastal leftovers, unused overloads and helpers, the version
counter of the retired drought memo, CSS for classes nothing renders.

**Why.** James: "Please perform a round of bug check and redundant code, and implement fixes for the lot."
— James, 23 September 2026.

### W-22 · The fire outlook: every forecast hour's index, each day's worst hour

**The decision.** The forecast carries the forest fire danger index for every hour, from that hour's own
values, and each day's figure is its worst hour with the values of that hour. The drought is carried
forward day by day as the Bureau's turns, at 9 am: the deficit stepped through each forecast day's rain and
heat, the factor recomputed against it and the rain window moved on. It is drawn from the forecast
station's own drought, or the nearest station in reach that holds one; without one, no index.

**Why.** James: "Implement the lot. Especially those forecasted indices." The pre-W-1 outlook put the
day's maximum temperature, least humidity and strongest wind into one index - an hour that seldom happens,
so the day read worse than it was. An index per hour costs nothing more and is the honest figure; the
worst hour is what a day is remembered by.
— James, 25 September 2026.

### W-23 · Fire ban districts, the CFS rating, total fire bans

**The decision.** The CFS's fifteen fire ban districts, from its own shapes file, give every station and
every reading its district; the CFS's ratings feed gives the AFDRS rating, the Fire Behaviour Index and
the total fire ban for today and the days ahead. Both are read when asked - the shapes held a day, the
ratings an hour - never on a clock (W-15). Every published day carries its date; out of season the feed
still says "No Rating" for 1 May, and that is never given as today's. The map has a layer of the
districts in the colour of today's rating; the API has `/api/v1/districts.geojson`.

**Why.** James asked for everything the service had before the start-over, fire danger first. Published
beats derived: the rating the public were told stands beside the indices computed here. The shapes' rings
are read with their holes (Esri's clockwise outer, anticlockwise hole), so a district cut out of another
is not swallowed by it.
— James, 25 September 2026.

### W-24 · Grass fire danger: curing per district, both grass models, now and ahead

**The decision.** A Curing page holds each fire ban district's grass curing and fuel load, entered from the
CFS's weekly curing map. Every reading and forecast hour carries McArthur's grassland index and the AFDRS
grass Fire Behaviour Index, rating, rate of spread and flame height on it, and every forecast day the worst
hour of each. The two models and their tests come back from before the start-over unchanged.

**Why.** The grass index cannot be computed without curing, and nobody publishes curing openly - the CFS
GeoHub carries no layer for it, checked again. An entered figure, dated and marked old after a fortnight,
is the honest source; a district without one has no grass index and says so. The fuel load was added beside
it (4.5 t/ha by default) because both models need it and the land-use class that used to supply it went
with the hexagons.
— James, 25 September 2026.

### W-25 · The Bureau's warnings, flood warnings among them

**The decision.** The Bureau's South Australian warnings are read when asked - the listing by conditional GET,
each product once while the listing names it - and held ten minutes. Every area a warning names is kept: public
districts, fire weather districts, river basins. A reading carries the warnings naming its public district or
its fire weather district in full, and every other warning in the state by title. The map shows a banner while
any is in force.

**Why.** Flood warnings were the gap. The old reader kept only public districts, and a flood warning is filed
by river basin, so it matched nothing and vanished. Matching by what can be matched and listing the rest -
rather than drawing basins nobody publishes as shapes - means a warning in force is never silently absent from
a reading. There were no warnings in force anywhere in the country the day this was built; the parser is tested
on a real severe weather warning kept from before the start-over.
— James, 25 September 2026.
