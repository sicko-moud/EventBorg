import { sendFcm } from "./fcm.js";
import { clientIp, fail, fold, json, limited, readJson } from "./util.js";

const COOLDOWN = 3 * 3600;
const DAY = 86400 * 1000;
const STORY_KINDS = new Set(["venue", "organizer", "business"]);

let memo = { at: 0, list: null };

export async function loadSources(env) {
  if (memo.list && Date.now() - memo.at < 600000) return memo.list;
  const r = await fetch(`${env.PAGES_URL}/sources.json`, { cf: { cacheTtl: 600, cacheEverything: true } });
  if (!r.ok) {
    if (memo.list) return memo.list;
    throw new Error(`sources.json HTTP ${r.status}`);
  }
  memo = { at: Date.now(), list: (await r.json()).sources || [] };
  return memo.list;
}

export const storyUrl = (src) => (src.ig ? `https://www.instagram.com/stories/${src.ig}/` : src.fb || null);

// Only listed venues, organizers and businesses with a profile we can open can raise story alerts.
export const eligible = (src) => Boolean(src && STORY_KINDS.has(src.kind) && storyUrl(src));

export function byHandle(sources, username) {
  const u = String(username || "").toLowerCase().replace(/^@/, "");
  return (u && sources.find((s) => eligible(s) && String(s.ig || "").toLowerCase() === u)) || null;
}

const escapeRx = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// Matches free text (a forwarded story's caption, a DM) against @handles and display names, on word boundaries.
export function byText(sources, text) {
  const t = fold(text);
  if (!t.trim()) return null;
  let best = null;
  for (const s of sources) {
    if (!eligible(s)) continue;
    const needles = [s.ig && `@${s.ig}`, s.ig, ...(s.names || [])].filter((n) => n && fold(n).length >= 4).map(fold);
    for (const n of needles) {
      const rx = new RegExp(`(^|[^\\p{L}\\p{N}_])${escapeRx(n)}(?=$|[^\\p{L}\\p{N}_])`, "u");
      if (rx.test(t) && (!best || n.length > best.len)) best = { src: s, len: n.length };
    }
  }
  return best?.src || null;
}

export function storyMessage(alert) {
  return {
    condition: `'st_all' in topics || 'st_${alert.sourceId}' in topics`,
    data: { type: "story", sourceId: alert.sourceId, name: alert.name, url: alert.url, ts: String(alert.ts) },
    android: { priority: "HIGH", ttl: "10800s", collapse_key: `st_${alert.sourceId}`, restricted_package_name: "app.eventborg" },
  };
}

export async function raiseStory(env, ctx, src, ts = Date.now()) {
  if (!eligible(src)) return { ok: false, err: "unknown_source" };
  if (await env.KV.get(`cd:${src.id}`)) return { ok: true, cooldown: true };
  await env.KV.put(`cd:${src.id}`, "1", { expirationTtl: COOLDOWN });
  const alert = { sourceId: src.id, name: src.n, url: storyUrl(src), ts };
  const list = (await env.KV.get("stories", "json")) || [];
  const fresh = [alert, ...list.filter((a) => a.sourceId !== src.id && ts - a.ts < DAY)].slice(0, 100);
  await env.KV.put("stories", JSON.stringify(fresh), { expirationTtl: 86400 });
  const send = sendFcm(env, storyMessage(alert)).catch((e) => console.log(`story fcm: ${e.message}`));
  ctx ? ctx.waitUntil(send) : await send;
  return { ok: true, cooldown: false };
}

export async function storySeen(req, env, ctx) {
  if (await limited(env.RL_STORY, clientIp(req))) return fail("rate_limited", 429);
  const { body, err } = await readJson(req, 512);
  if (err) return fail(err, err === "too_large" ? 413 : 400);
  const now = Date.now();
  const ts = Number(body.ts);
  if (!Number.isFinite(ts) || ts < now - 30 * 60000 || ts > now + 120000) return fail("bad_ts", 400);
  const src = (await loadSources(env)).find((s) => s.id === body.sourceId);
  if (!eligible(src)) return fail("unknown_source", 400);
  const res = await raiseStory(env, ctx, src, Math.min(ts, now));
  return json({ ok: true, cooldown: res.cooldown });
}

export async function listStories(env) {
  const now = Date.now();
  const stories = ((await env.KV.get("stories", "json")) || []).filter((a) => now - a.ts < DAY);
  return json({ v: 1, stories }, 200, { "cache-control": "public, max-age=60" });
}
