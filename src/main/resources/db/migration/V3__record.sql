-- The record (W-6): what a real station's readings are kept as once the file has moved on. Every
-- ten-minute observation is consolidated into a six-hour window ending at 3 am, 9 am, 3 pm or 9 pm
-- local, and the windows into the Bureau's day - the 24 hours from 9 am - which is the pair of
-- numbers the drought index integrates: rain and the maximum. Both kept 548 days, a year and a half,
-- so a year's spin-up is always there.

create table if not exists station_hour6 (
    station_id     varchar(24) not null,
    at             timestamptz not null,   -- the window's end, on a 3/9/15/21 local hour
    readings       integer     not null,
    temp_min_c     double precision,
    temp_max_c     double precision,
    temp_mean_c    double precision,
    rh_min_pct     integer,
    rh_max_pct     integer,
    wind_mean_kmh  double precision,
    wind_max_kmh   double precision,
    gust_max_kmh   double precision,
    rain_since_9am_mm double precision,    -- at the window's last reading
    rain_24h_mm    double precision,       -- the Bureau's total to 9 am, as published at the last reading
    published_max_c double precision,      -- the Bureau's running maximum at the last reading
    primary key (station_id, at)
);
create index if not exists ix_station_hour6_at on station_hour6 (at);

-- One row per station per Bureau day: the day dated by the 9 am it begins at, its rain the total to
-- 9 am the next day, its maximum the highest reading in between. The source says where the row came
-- from: the station's own file, or Open-Meteo's archive filling a day the file did not cover. A
-- station's own row is never replaced by the archive's.
create table if not exists station_day (
    station_id  varchar(24) not null,
    day         date        not null,
    rain_mm     double precision,
    max_temp_c  double precision,
    source      varchar(8)  not null,
    written_at  timestamptz not null,
    primary key (station_id, day)
);
create index if not exists ix_station_day_day on station_day (day);
