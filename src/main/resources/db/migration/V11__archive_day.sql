-- The archive as it was fetched (W-21): every rain day Open-Meteo returned for a point - the hexagon's
-- centre - kept raw and apart from what any hexagon's drought did with it, so a change to the rule
-- that picks a hexagon's stations, or to the reach, recomputes every drought from this and the
-- stations' ledger without a fetch. Keyed on the point, not the hexagon: it is what was asked for
-- where. The recent-days call's rows are kept too, marked, and give way to the archive's when the
-- archive later covers the day.
create table if not exists archive_day (
    lat               double precision not null,
    lon               double precision not null,
    day               date             not null,
    rain_mm           double precision not null,
    max_temperature_c double precision not null,
    source            varchar(16)      not null,
    fetched_at        timestamptz      not null,
    primary key (lat, lon, day)
);
create index if not exists ix_archive_day_day on archive_day (day);
