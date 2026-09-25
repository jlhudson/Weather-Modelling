-- The land cover at a place (W-38), from Digital Earth Australia's Landsat land cover, Collection 3: what grows there,
-- which decides the AFDRS fuel - forest or grass - and so which fire behaviour index is the place's rating. Looked up
-- once per cell of about 200 m and kept; the land cover is annual.
create table if not exists land_cover (
    cell       text primary key,
    level3     integer,
    level4     integer,
    label      text,
    year       integer,
    fetched_at timestamptz not null
);
