# Docs

Five files, in the order they are worth reading.

| | |
|---|---|
| [01-what-it-does.md](01-what-it-does.md) | One point in, the weather out. The anchor cache, the provider chain, the governor, drought and flood — and what is deliberately not here. |
| [02-api.md](02-api.md) | The wire contract, with curl. Field names are the contract. |
| [03-configuration.md](03-configuration.md) | Every `weather.*` key with its default, and every environment variable. |
| [04-migration-from-the-hub.md](04-migration-from-the-hub.md) | The four tables to dump and restore, and the Hub's side of the wiring. |
| [05-decisions.md](05-decisions.md) | The two decisions that produced this repository, and the three it took for itself. |

**Where the rest of the reasoning lives.** The moved code still cites the Hub's docs by number —
`docs/03-sources.md` for host budgets, `docs/09` for the cache and the fire indices, `docs/13` for why
a stale reading must say so, `docs/24` for the band vocabularies, `docs/26` for the diagnostics shape,
and `D-nnn` for `docs/16-decisions.md`. Those citations were not rewritten, because the reasoning has
not moved: it is still in
[The-Hub-Database/docs](https://github.com/jlhudson/The-Hub-Database/tree/main/docs), and a reference
pointing at where an argument was actually made is more useful than one pointing at a file that would
have to repeat it.
