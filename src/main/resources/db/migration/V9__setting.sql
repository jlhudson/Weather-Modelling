-- What the console sets and a restart must keep, one row per value: today the station reach, how far
-- outside a hexagon a station still counts as its own (W-18). The value is text; the reader knows its
-- shape. Not the properties file: these are turned on the map, not in a deployment.
create table if not exists setting (
    key        varchar(64) primary key,
    value      text        not null,
    updated_by varchar(64),
    updated_at timestamptz not null
);
