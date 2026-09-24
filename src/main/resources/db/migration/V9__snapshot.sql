-- The readings kept for a caller's reference (W-27): an ask that names what it is for - The Hub sends an
-- incident's id - has its reading kept, at most once every three hours a reference, so a question asked later
-- about that moment is answered with what was said then. Kept 548 days, as the record is.
create table if not exists reading_snapshot (
    id     bigserial primary key,
    ref    text not null,
    lat    double precision not null,
    lon    double precision not null,
    at     timestamptz not null,
    body   jsonb not null
);
create index if not exists ix_reading_snapshot_ref_at on reading_snapshot (ref, at);
create index if not exists ix_reading_snapshot_at on reading_snapshot (at);
