-- The drought areas (W-11): one state per fixed area of hexagons - a hexagon and its ring at radius 1 -
-- spun up once at the area's centre and stepped once a day, shared by every hexagon in the area.
-- Each hexagon still carries a copy of its area's state on its own row (hexagon.drought), so a
-- reading is answered from the hexagon; this table is where the state lives and is stepped.
create table if not exists drought_area (
    id           varchar(24) primary key,
    lat          double precision not null,
    lon          double precision not null,
    radius       int         not null,
    zone         varchar(48) not null,
    computed_for date        not null,
    state        jsonb       not null,
    updated_at   timestamptz not null
);
