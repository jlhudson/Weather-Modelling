-- The grid the hexagon-keyed tables were written with: its width and its anchor. Read at startup and
-- compared with the code's Grid; a change means every hexagon id changed, and the hexagons, the
-- history, the drought areas and the river cells are reset (they are rebuilt from the sources; the
-- history is the one loss, and re-gridding accepts it).
create table if not exists grid_spec (
    id    int primary key,
    spec  text        not null,
    since timestamptz not null
);

-- A snapshot is written for an ask that carries a ref - what the reading is for - not for an incident:
-- the service answers a reading for any reason, and the caller names it.
do $$
begin
    if exists (select 1 from information_schema.columns where table_name = 'reading_snapshot' and column_name = 'incident') then
        alter table reading_snapshot rename column incident to ref;
    end if;
end $$;

-- A database that already holds hexagons was written with the grid before this migration - 15 km,
-- laid out from the projection's own origin - so that is recorded, and the first start on the new
-- grid resets what was keyed on it. An empty database records the code's grid on its first start.
insert into grid_spec (id, spec, since)
select 1, 'hexagons 15.0 km, from the projection origin (before V3)', now()
where exists (select 1 from hexagon) and not exists (select 1 from grid_spec where id = 1);
