-- The ground around each station, sampled once from Open-Meteo's elevation model (W-2): the station's
-- own height and the height every kilometre out to fifty along forty-eight bearings, as eight-byte
-- doubles, the station's own first. The reach polygon is drawn from this by arithmetic whenever the
-- rule is turned; nothing about the polygon itself is stored. The position is the one sampled at: a
-- station that moves is sampled again.
create table if not exists terrain (
    station_id varchar(16)      primary key,
    lat        double precision not null,
    lon        double precision not null,
    elevations bytea            not null,
    sampled_at timestamptz      not null,
    calls      integer          not null
);
