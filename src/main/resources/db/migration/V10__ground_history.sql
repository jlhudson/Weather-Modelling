-- History is the ground's, not the hexagon's (W-19): what a station measured, consolidated every six
-- hours and kept five years; the daily rain and maximum temperature a hexagon's drought was stepped
-- with, from whichever source supplied the day, so the archive is fetched once and never again; and
-- the model's "now" for a hexagon on the moments it stood in for a station that was not there.
-- The hexagon snapshots go: a reading that was is answered from these.

-- The six-hourly ledger row becomes a consolidation of the readings seen in its window rather than
-- the reading at its moment: the window's extremes and means, and how many readings made them.
-- The existing columns stay as they were - the values at the row's moment, the day's rain to 9 am
-- and its running maximum - which is what the drought reads.
alter table station_sample
    add column if not exists readings       integer,
    add column if not exists temp_min_c     double precision,
    add column if not exists temp_max_c     double precision,
    add column if not exists temp_mean_c    double precision,
    add column if not exists rh_min_pct     integer,
    add column if not exists rh_max_pct     integer,
    add column if not exists wind_mean_kmh  double precision,
    add column if not exists wind_max_kmh   double precision,
    add column if not exists gust_max_kmh   double precision;

-- One row per hexagon per day: the rain and the day's maximum the drought was stepped with, and
-- where they came from (stations, archive, recent). Keyed on the hexagon, so a re-gridding resets it.
create table if not exists drought_day (
    hexagon_id        varchar(24)      not null,
    day               date             not null,
    rain_mm           double precision not null,
    max_temperature_c double precision not null,
    source            varchar(16)      not null,
    written_at        timestamptz      not null,
    primary key (hexagon_id, day)
);
create index if not exists ix_drought_day_day on drought_day (day);

-- The model standing in: the series read at the moment of a fetch, for a hexagon whose "now" was
-- not from the ground then. One row per hexagon per fetch.
create table if not exists model_now (
    hexagon_id             varchar(24)      not null,
    at                     timestamptz      not null,
    fetched_at             timestamptz      not null,
    upstream               varchar(32),
    model                  varchar(64),
    temperature_c          double precision,
    apparent_temperature_c double precision,
    dew_point_c            double precision,
    humidity_pct           integer,
    wind_speed_kmh         double precision,
    wind_direction_deg     integer,
    wind_gust_kmh          double precision,
    precipitation_mm       double precision,
    pressure_msl_hpa       double precision,
    cloud_cover_pct        integer,
    condition              varchar(32),
    primary key (hexagon_id, at)
);
create index if not exists ix_model_now_at on model_now (at);

drop table if exists reading_snapshot;
alter table hexagon drop column if exists last_snapshot_at;
