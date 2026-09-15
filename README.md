# Anvil

A small Python service that answers one question about a point: **what is the weather, and how
dangerous is it?**

- Weather now, and the forecast
- Fire index now, and the fire index forecast

One upstream: the **Google Weather API**. One cache, in the application, keyed on time, distance and
height. Nothing else.

**Nothing is built.** Open questions are in [docs/QUESTIONS.md](docs/QUESTIONS.md) — eight of them,
and one is the only one that really matters.
