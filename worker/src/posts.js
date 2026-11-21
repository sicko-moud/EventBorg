import { buildPrompt, cleanDraft, fixYear, PROMPT_VERSION, scrub } from "./draft.js";
import { gemini, today, weekday } from "./extract.js";
import { putItem } from "./inbox.js";
import { eligible, loadSources } from "./stories.js";
import { clientIp, fail, json, limited, readJson } from "./util.js";

// One extraction per source per 20 min, whichever phone reports first; later reports get the cached verdict.
const COOLDOWN = 20 * 60;
const MAX_BODY = 1_500_000;
const B64 = /^[A-Za-z0-9+/]+={0,2}$/;

// A phone's report of a listed account's new post: the notification text and, if Instagram attached it, the picture.
export function parsePost(body, now = Date.now()) {
  const ts = Number(body?.ts);
  if (typeof body?.sourceId !== "string" || !Number.isFinite(ts) || ts < now - 30 * 60000 || ts > now + 120000) return null;
  const text = scrub(String(body.text || "").replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, " ")).slice(0, 1000);
  const img = typeof body.img === "string" && body.img.length < MAX_BODY && B64.test(body.img.slice(0, 4000)) ? body.img : null;
  return { sourceId: body.sourceId, ts: Math.min(ts, now), text, img };
}

export function postPrompt(src, text, day) {
  const who = src.ig ? `${src.n} (Instagram @${src.ig})` : src.n;
  return buildPrompt(day, [
    `A new social-media post by ${who}, a public account in Sønderborg Municipality, as shown in a phone notification` +
      ` (the attached image, if any, is the post's picture):\n${text || "(no text)"}`,
  ]);
}

export async function postSeen(req, env, ctx) {
  if ((await limited(env.RL_EXTRACT, clientIp(req))) || (await limited(env.RL_GLOBAL, "extract"))) return fail("rate_limited", 429);
  const { body, err } = await readJson(req, MAX_BODY + 4000);
  if (err) return fail(err, err === "too_large" ? 413 : 400);
  const p = parsePost(body);
  if (!p) return fail("bad_request", 400);
  const src = (await loadSources(env)).find((s) => s.id === p.sourceId);
  if (!eligible(src)) return fail("unknown_source", 400);

  const key = `po:${src.id}`;
  const seen = await env.KV.get(key, "json");
  if (seen) return json({ ok: true, cached: true, is_event: Boolean(seen.e), title: seen.t || null });
  if (!p.text.trim() && !p.img) return json({ ok: true, is_event: false, title: null });
  await env.KV.put(key, JSON.stringify({ e: false }), { expirationTtl: COOLDOWN });

  const parts = [{ text: postPrompt(src, p.text, `${weekday()} ${today()}`) }, ...(p.img ? [{ inline_data: { mime_type: "image/jpeg", data: p.img } }] : [])];
  const res = await gemini(env, parts);
  if (!res) {
    await env.KV.delete(key);
    return fail("llm_unavailable", 503);
  }
  const draft = fixYear(cleanDraft({ ...res, organizer: res.organizer || src.n }), today(), p.text);
  const isEvent = Boolean(res.is_event && draft);
  if (isEvent) {
    const conf = Math.max(0, Math.min(1, Number(res.confidence) || 0));
    await putItem(env, ctx, { channel: "post", type: "post", text: p.text || null, media: [], sender: null, source: src.id, draft, conf, pv: PROMPT_VERSION });
  }
  await env.KV.put(key, JSON.stringify({ e: isEvent, t: isEvent ? draft.title : null }), { expirationTtl: COOLDOWN });
  return json({ ok: true, is_event: isEvent, title: isEvent ? draft.title : null });
}
