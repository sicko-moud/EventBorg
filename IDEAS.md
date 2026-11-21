# IDEAS

## M1 — web ingest
- [done] Per-source refresh interval + conditional GET (ETag/Last-Modified): far fewer requests and LLM calls. Value high, cost low.
- [done] Keep IDs stable across runs by re-using the previous run's ID when an event dedupe-matches it: no duplicate pushes when the primary source changes. Value high, cost low.
- [done] Source health in sources.json (status, last ok, event count, failure streak) for the app's Sources screen. Value medium, cost low.
- [M7] Subscribable .ics feeds per category next to events.json. Value medium, cost low-medium.
- [M7] Venue geocoding table (lat/lng for top venues via OSM Nominatim, cached in state, 1 req/s + attribution) for a map view and a tighter municipality check. Value medium, cost medium.
