-- The published fire danger per fire ban district per day (W-45), kept for ever: what the CFS told the
-- public, first as a forecast and then on the day, so an incident can be read against the danger of
-- its day long after the feed has moved on. Held by The Hub as its own fire_danger_day until
-- 28 September 2026. Not the weather: the admin reset keeps it.
create table if not exists fire_danger_day (
    district       text not null,
    rating_date    date not null,
    name           text,
    number         integer,
    rating         text not null,
    fbi            integer,
    total_fire_ban boolean not null,
    published      boolean not null,
    first_seen_at  timestamptz not null,
    last_seen_at   timestamptz not null,
    primary key (district, rating_date)
);

create index if not exists ix_fire_danger_day_date on fire_danger_day (rating_date);
