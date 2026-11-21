# IDEAS

## M1 — web ingest
- [done] Per-source refresh interval + conditional GET (ETag/Last-Modified): far fewer requests and LLM calls. Value high, cost low.
- [done] Keep IDs stable across runs by re-using the previous run's ID when an event dedupe-matches it: no duplicate pushes when the primary source changes. Value high, cost low.
- [done] Source health in sources.json (status, last ok, event count, failure streak) for the app's Sources screen. Value medium, cost low.
- [M7] Subscribable .ics feeds per category next to events.json. Value medium, cost low-medium.
- [M7] Venue geocoding table (lat/lng for top venues via OSM Nominatim, cached in state, 1 req/s + attribution) for a map view and a tighter municipality check. Value medium, cost medium.

## M2 — push + Worker
- [done] Quiet hours: hold event pushes 22:00–08:00 Copenhagen and send them (grouped) in the morning. Value high, cost low.
- [done] Silent backfill: a source adding ≥10 new events that are ≥half its listing in one run is treated like a first ingest. Value high, cost low.
- [done] Flood guard: when one run has more than 8 pushable events, every category with ≥2 of them is grouped. Value medium, cost low.
- [done] /extract reads a shared link's public page (robots.txt checked, JSON-LD + main text) so "share a link" works, not only text/images. Value high, cost medium.
- [done] /extract returns a signed token of the LLM result; /submit keeps the model's confidence only if the token verifies, so clients can't self-approve. Value high, cost low.

## M3 — social inbox + story relay
- [done] Social tokens stay in the Worker: the inbox job reads media, polls Business Discovery and sends replies through authenticated pass-through endpoints (one GitHub secret instead of five). Value high (security), cost low.
- [done] Instant approval: the workflow also runs on `issues: labeled`, so an `approve` label publishes within ~1 min instead of ≤15. Value medium, cost low.
- [done] Editable moderation: the Issue holds the fields as YAML; corrections made in the Issue before adding `approve` are what gets published. Value high, cost low.
- [done] Relative dates ("i aften", "på lørdag") resolved against the post/message date, not the run date. Value medium, cost low.
- [M7] Device-reported stories need 2 reports from different IPs before the relay pushes (abuse guard once there are enough sharers). Value medium, cost low-medium.

## M4 — app core
- [done] Quick filter chips under search (Now, Tonight, Weekend, Free) as shortcuts into the saved filter. Value high, cost low.
- [done] Long-running events (exhibitions) that already started go to an "Ongoing" section at the end instead of filling Today. Value high, cost low.
- [done] Source page: tap a source to see its links (web/IG/FB), star it and its upcoming events. Value medium, cost low.
- [done] Search folds Danish spellings both ways (ø/oe→o, æ→ae, å/aa→a, accents stripped). Value medium, cost low.
- [M7] Map view of today's events by venue (needs the M7 geocoding table). Value medium, cost medium.
