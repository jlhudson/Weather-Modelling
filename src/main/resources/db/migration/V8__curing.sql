-- Grass curing per fire ban district (W-24): how dry the grass is, entered on the console from the CFS's
-- weekly curing map in fire season - no open feed publishes it - with the fuel load the grass indices
-- are drawn for. What the operator entered, not the weather: the admin reset keeps it.
create table if not exists grass_curing (
    district      text primary key,
    percent       integer not null,
    fuel_load_t_ha double precision not null default 4.5,
    entered_on    date not null,
    source        text,
    updated_by    text,
    updated_at    timestamptz not null
);
