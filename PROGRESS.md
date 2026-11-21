# PROGRESS

**M1 Web ingest — done (2026-11-21).** Next: M2.

Status
- 23 sources in sources.yaml: 1 tribe, 2 jsonld, 17 html_llm, ticketmaster, 2 social-only (`type: none`). Handles come from each site's own links.
- Pipeline: `python -m pipeline run [--force] [--only a,b] [--no-llm]` writes out/events.json, out/sources.json, out/state/*.
- Local run without keys: 242 events (sonderborg-dk 233, koncertsalen-alsion 38, sdjsymfoni 9; 286 records before dedupe). 20 pytest tests pass.
- Workflow .github/workflows/pipeline.yml: cron every 15 min + repository_dispatch `inbox`, one concurrency group, restores and force-pushes the orphan `data` branch. test.yml runs pytest.
- New-event diff is computed; pushable IDs are stored in state/meta.json `push` for M2.

Open issues
- Repo not created yet: `gh` is not logged in. Then enable Pages from branch `data` /root and add secrets.
- html_llm sources (17) are waiting on GEMINI_API_KEY; ticketmaster is waiting on TICKETMASTER_KEY.
- nordborghus: robots.txt was unreachable once (retried automatically each interval).
- ig handles still unknown for Mejeriet, Koncertsalen Alsion, Universe, Nordborghus, Skansen, Sønderborg Teater.

Next step
- M2: FCM push sender reading state/meta.json `push` (grouping, topics, 60-day rule); Worker app endpoints + static pages.
