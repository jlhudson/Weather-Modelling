# Migrations

Flyway owns **the changes `ddl-auto: update` cannot make**. It does not own the schema.

Hibernate still creates tables and adds nullable columns, as it has since D-091. What it has never
been able to do is add a `NOT NULL` column to a table that already has rows, put a foreign key over
existing data, narrow a type, or rename a column instead of adding a new one beside it and orphaning
the old. Those are migrations now, rather than the database reset D-206 had to reach for.

**There are no migrations here yet, and that is the expected state.** This directory is empty apart
from this file. Flyway is wired up on day one so that the day one of the four tables needs a change
`update` cannot make, the answer is a file rather than an argument about whether to introduce Flyway
under pressure. The four tables are `weather_anchor`, `weather_call`, `drought_cell` and `river_cell`.

## The one rule

**A migration runs before Hibernate, so on a fresh database its table does not exist yet.**

Spring Boot orders `FlywayMigrationInitializer` ahead of the entity manager. On the live database
every table is already there and a migration does its work. On an empty one — a new developer, a test
box, the first deploy of a new environment — Flyway runs against nothing, and then Hibernate creates
the tables from the entities, which already declare whatever the migration was going to add.

So every migration is written to **no-op when its table is not there**, and both paths arrive at the
same schema:

```sql
ALTER TABLE IF EXISTS drought_cell ADD COLUMN IF NOT EXISTS spin_up_days integer;
UPDATE drought_cell SET spin_up_days = 365 WHERE spin_up_days IS NULL;
ALTER TABLE IF EXISTS drought_cell ALTER COLUMN spin_up_days SET NOT NULL;
```

`ALTER TABLE IF EXISTS` is the whole trick. Without it a fresh database fails on the first boot.

`baseline-version` is `0` for the same reason. With Flyway's default of `1`, `V1__*` would run on an
empty database and be skipped on the populated one — the asymmetry that makes hybrid setups quietly
diverge. At `0` every migration runs everywhere, exactly once.

## What a migration may not do

- **Never `DROP TABLE` or `DROP COLUMN`.** Retiring data is an operator's act (D-082). Leave the
  column, stop writing it, and delete it by hand once you have looked at what is in it.
- **Never `DELETE` or `TRUNCATE`.** A migration that loses rows is a migration nobody can trust to
  run unattended, and this database holds `drought_cell` — a year of integrated rainfall per cell,
  rebuilt from nothing if lost (docs/27 27.4: dump and restore them, do not re-derive).
- **Never assume a column's contents.** Backfill with a `WHERE ... IS NULL` so re-running against a
  half-migrated database is safe.

## When Flyway can own the schema outright

When every table has a migration behind it, `ddl-auto` becomes `validate` in production and the
entities stop being the schema. That needs a real baseline dumped from a live database, which is a
deliberate piece of work and not something to do by hand from the entity classes. Until then this
hybrid holds, and `update` stays.

## Naming

`V<n>__lower_snake_description.sql`, one concern per file, never edited once it has run anywhere —
Flyway checksums them and a changed file fails the next boot. Fix a mistake with a new migration.
