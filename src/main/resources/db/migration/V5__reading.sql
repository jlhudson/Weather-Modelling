-- The readings (W-15): every ten-minute observation the Bureau's file brings, as published, one row
-- per station per observation time; and a point of ours' current from the model, the same way. The
-- database is the store: a restart reads the latest back, and the day's history is folded from
-- these rows once a day, not from what happened to be in memory. Kept three days - long enough to
-- fold a day the housekeeping missed - then gone; the six-hour windows and the days are the history.
create table if not exists station_reading (
    station_id        varchar(24) not null,
    at                timestamptz not null,
    temp_c            double precision,
    apparent_c        double precision,
    dew_point_c       double precision,
    humidity_pct      integer,
    wind_kmh          double precision,
    wind_deg          integer,
    wind_dir          varchar(4),
    gust_kmh          double precision,
    pressure_hpa      double precision,
    rain_since_9am_mm double precision,
    rain_24h_mm       double precision,
    max_temp_c        double precision,
    min_temp_c        double precision,
    visibility_km     double precision,
    cloud             varchar(64),
    cloud_oktas       integer,
    delta_t_c         double precision,
    primary key (station_id, at)
);
create index if not exists ix_station_reading_at on station_reading (at);
