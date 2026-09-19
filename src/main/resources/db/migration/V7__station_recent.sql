-- W-16: a station's last few readings, kept across a restart so a wind change is seen from the first
-- file read after one, not an hour later. Six per station; the register prunes as it writes.
create table if not exists station_recent (
    station_id        varchar(16)      not null references station (id) on delete cascade,
    at                timestamptz      not null,
    temperature_c     double precision,
    humidity_pct      integer,
    wind_kmh          double precision,
    wind_deg          integer,
    gust_kmh          double precision,
    rain_since_9am_mm double precision,
    primary key (station_id, at)
);
