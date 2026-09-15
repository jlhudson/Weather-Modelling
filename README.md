# Anvil

**The weather service.** One point, one answer: **now and the next seventy-two hours** — weather, the
drought factor, flood and rain, and the fire indices — split out of
[The Hub Database](https://github.com/jlhudson/The-Hub-Database) and rebuilt in Python.

The name exists to end an ambiguity. The Hub's `WeatherManager` decides *when* an incident is worth
asking about; **Anvil** answers. So "the weather manager" is always the Hub's, and "Anvil" is always
the service — and nothing is called "the weather model" again.

An anvil is the flat top of a cumulonimbus: the visible sign that the atmosphere has turned
dangerous, and the shape a fire makes once it grows big enough to build its own weather.

---

**Nothing is built yet.** The scope change was put on 15 September 2026 and two rounds of questions
have been answered against it. What is decided so far — a greenfield Python service in Docker, a
pluggable model, five endpoints behind a Cloudflare tunnel, and curing derived from satellite
greenness rather than typed in — is recorded as `W-001` to `W-024`.

**Start here → [docs/QUESTIONS.md](docs/QUESTIONS.md).** Fifty-two questions, five of them blocking
the architecture. Answer in place on the `**A:**` lines; answers get applied and logged.
