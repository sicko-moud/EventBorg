# PROGRESS

**M1 Web ingest — done and live (2026-11-21).** Next: M2.

Status
- Repo https://github.com/sicko-moud/EventBorg (public). Pages from branch `data`: https://sicko-moud.github.io/EventBorg/events.json and /sources.json (ETag, max-age=600).
- First CI run: 266 events from 10 sources (sonderborg-dk 238, koncertsalen-alsion 39, sonderborg-teater 27, musikskolen 10, sdjsymfoni 9, brinckmanns 7, sergenten 4, others 1–2). 90 summaries; the rest fill in at 25 LLM calls per run.
- 23 sources in sources.yaml: 1 tribe, 2 jsonld, 17 html_llm, ticketmaster (`disabled` until TICKETMASTER_KEY), 2 social-only.
- Secrets: GEMINI_API_KEY. Variables: GEMINI_MODEL, LLM_MAX_CALLS. CONTACT_EMAIL is optional; the User-Agent carries the repo URL.
- `python -m pipeline run [--force] [--only a,b] [--no-llm]`; 21 pytest tests pass. Pushable new-event IDs are in state/meta.json `push` for M2.

Open issues
- LLM: gemini-flash-lite-latest is primary; gemini-flash-latest only retries calls lite fails on (`llm_fallbacks=` in the run summary).
- Lite can return a truncated event list; html_llm checks events vs. page links and asks flash-latest (or lite again) for a second opinion. Both models 503 often; failed pages keep their last events and retry every run.
- For M2: a source recovering from a truncated extraction makes its backlog look new (37 pushable in one run). Treat many new events from one source in one run as silent backfill.
- ig handles still unknown for Mejeriet, Koncertsalen Alsion, Universe, Nordborghus, Skansen, Sønderborg Teater.

Next step
- M2: FCM push sender reading state/meta.json `push` (grouping, topics, 60-day rule); Worker app endpoints + static pages.
