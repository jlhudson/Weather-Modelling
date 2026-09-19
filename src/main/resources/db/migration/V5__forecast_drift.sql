-- Every comparison of a station against the forecast in its hexagon (W-12): one row per new station
-- observation while a forecast is held, so "how good are the forecasts here" is a question the
-- ledger answers. A row marked drifted is a forecast thrown out. Never read to answer a request.
create table if not exists forecast_drift (
    id            bigserial primary key,
    hexagon_id    varchar(24)      not null,
    at            timestamptz      not null,
    station_id    varchar(16)      not null,
    upstream      varchar(32),
    temperature_c double precision,
    humidity_pct  int,
    wind_kmh      double precision,
    rain_mm       double precision,
    score         double precision not null,
    worst         varchar(16),
    drifted       boolean          not null,
    created_at    timestamptz      not null
);
create index if not exists ix_forecast_drift_hexagon_at on forecast_drift (hexagon_id, at desc);
create index if not exists ix_forecast_drift_at on forecast_drift (at desc);
