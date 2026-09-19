# Docs

Six files, in the order they are worth reading. The first five describe the service as it runs since
the overhaul of 19 September 2026; the sixth is the catalogue that overhaul was built from.

| | |
|---|---|
| [01-what-it-does.md](01-what-it-does.md) | The weather, and the fire danger, at a point. Hexagons, the upstreams and their budget, the Bureau's stations and warnings, the CFS rating and curing, drought stepped daily, terrain and land use, the fire picture, history. |
| [02-api.md](02-api.md) | Version 1 of the API, with curl. The reading's shape is the contract, written down as a JSON Schema both repositories keep. |
| [03-configuration.md](03-configuration.md) | Every setting with its default, every environment variable, and where the constants live. |
| [04-migration-from-the-hub.md](04-migration-from-the-hub.md) | The database migration in place, what the Hub changed, what a second copy would need. |
| [05-decisions.md](05-decisions.md) | The three Hub decisions that produced this repository, and the twelve this service took. |
| [06-overhaul.md](06-overhaul.md) | **The overhaul catalogue**, ticked: the twenty items, what each changed and why. |

**Where the rest of the reasoning lives.** The science still cites the Hub's docs by number — `docs/09`
for the fire indices, `docs/13` for why a stale reading must say so, `docs/24` for the band
vocabularies, `docs/26` for the diagnostics shape, and `D-nnn` for `docs/16-decisions.md`. Those
citations point at where an argument was actually made, in
[The-Hub-Database/docs](https://github.com/jlhudson/The-Hub-Database/tree/main/docs).
