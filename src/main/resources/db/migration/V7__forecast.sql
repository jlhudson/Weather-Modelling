-- The forecasts (W-20): the model's latest answer for a station or a point of ours - its now, the hours
-- ahead and the days - one row a station, fetched when an ask finds none or one older than three hours
-- (the now, an hour, when the station's own file has gone quiet). Kept a day, then pruned.
create table if not exists station_forecast (
    station_id text primary key,
    fetched_at timestamptz not null,
    upstream   text not null,
    body       jsonb not null
);
