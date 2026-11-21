import { buildPrompt, cleanDraft, findUrl, fixYear, PROMPT_VERSION, SCHEMA } from "./draft.js";
import { readPage } from "./page.js";
import { clientIp, fail, json, limited, readJson, sign } from "./util.js";

const API = "https://generativelanguage.googleapis.com/v1beta/models/";
const MAX_BODY = 2_000_000;
const IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];

export const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Copenhagen" }).format(new Date());
export const weekday = () => new Intl.DateTimeFormat("en-GB", { timeZone: "Europe/Copenhagen", weekday: "long" }).format(new Date());

async function callModel(env, model, parts, timeoutMs) {
  const gen = {
    responseMimeType: "application/json", responseSchema: SCHEMA, temperature: 0.1, maxOutputTokens: 4096,
    thinkingConfig: { thinkingLevel: env.GEMINI_THINKING || "low" },
  };
  const post = (generationConfig) =>
    fetch(`${API}${model}:generateContent`, {
      method: "POST",
      headers: { "x-goog-api-key": env.GEMINI_API_KEY, "content-type": "application/json" },
      body: JSON.stringify({ contents: [{ role: "user", parts }], generationConfig }),
      signal: AbortSignal.timeout(timeoutMs),
    });
  let r = await post(gen);
  if (r.status === 400 && /thinking/i.test(await r.clone().text())) {
    const { thinkingConfig, ...rest } = gen;
    r = await post(rest);
  }
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  const cand = (await r.json()).candidates?.[0];
  const out = (cand?.content?.parts || []).filter((p) => !p.thought).map((p) => p.text || "").join("");
  return JSON.parse(out);
}

// Both free-tier models return 503 when busy: try primary, fallback, then primary again, within one time budget.
export async function gemini(env, parts) {
  const primary = env.GEMINI_MODEL || "gemini-flash-lite-latest";
  const fallback = env.GEMINI_FALLBACK_MODEL || "gemini-flash-latest";
  const deadline = Date.now() + 45000;
  for (const [i, m] of [primary, fallback, primary].entries()) {
    const left = deadline - Date.now();
    if (left < 5000) break;
    if (i === 2) await new Promise((r) => setTimeout(r, 1500));
    try {
      return await callModel(env, m, parts, Math.min(25000, left));
    } catch (e) {
      console.log(`gemini ${m}: ${e.message}`);
    }
  }
  return null;
}

export async function extract(req, env) {
  const ip = clientIp(req);
  if ((await limited(env.RL_EXTRACT, ip)) || (await limited(env.RL_GLOBAL, "extract"))) return fail("rate_limited", 429);
  const { body, err } = await readJson(req, MAX_BODY);
  if (err) return fail(err, err === "too_large" ? 413 : 400);
  const text = typeof body.text === "string" ? body.text.slice(0, 4000).trim() : "";
  const img = typeof body.img === "string" && /^[A-Za-z0-9+/=]+$/.test(body.img.slice(0, 200)) ? body.img : null;
  const mime = IMAGE_TYPES.includes(body.mime) ? body.mime : "image/jpeg";
  if (!text && !img) return fail("empty", 400);

  const link = findUrl(text);
  let page = null;
  if (link) {
    page = await readPage(link, env).catch(() => ({ err: "unreadable" }));
    const onlyLink = text.replace(link, "").trim().length < 15;
    if (page.err && onlyLink && !img) return fail(page.err === "blocked" ? "blocked_url" : "unreadable_url", 422);
  }
  const prompt = buildPrompt(`${weekday()} ${today()}`, [text && `Shared text:\n${text}`, page?.text && `Linked page (${page.url}):\n${page.text}`]);
  const parts = [{ text: prompt }, ...(img ? [{ inline_data: { mime_type: mime, data: img } }] : [])];
  const res = await gemini(env, parts);
  if (!res) return fail("llm_unavailable", 503);

  const haystack = `${text} ${page?.text || ""}`;
  const draft = fixYear(cleanDraft({ ...res, url: res.url || page?.url || link }), today(), haystack);
  const isEvent = Boolean(res.is_event && draft);
  const conf = Math.max(0, Math.min(1, Number(res.confidence) || 0));
  const reason = String(res.reason || "").slice(0, 200);
  if (!isEvent) return json({ ok: true, is_event: false, reason });
  const tok = env.SIGNING_KEY ? await sign(env.SIGNING_KEY, { d: draft, c: conf, v: PROMPT_VERSION, ts: Date.now() }) : null;
  return json({ ok: true, is_event: true, conf, reason, draft, tok });
}
