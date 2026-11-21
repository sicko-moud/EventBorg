import { cleanDraft } from "./draft.js";
import { clientIp, fail, json, limited, readJson, verify } from "./util.js";

const ITEM_TTL = 14 * 86400;
const TOKEN_AGE = 24 * 3600 * 1000;
const CORE = ["title", "start", "venue", "town"];

async function dispatch(env) {
  if (!env.GH_TOKEN) return console.log("dispatch skipped: no GH_TOKEN");
  const r = await fetch(`https://api.github.com/repos/${env.GH_REPO}/dispatches`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.GH_TOKEN}`, accept: "application/vnd.github+json",
      "x-github-api-version": "2022-11-28", "user-agent": "EventBorg-Worker",
    },
    body: JSON.stringify({ event_type: "inbox" }),
  });
  if (!r.ok) console.log(`dispatch failed: HTTP ${r.status}`);
}

// At most one repository_dispatch per 60 s; a request inside the window is flushed by the minute cron.
export async function requestRun(env, ctx) {
  if (await env.KV.get("lock:dispatch")) return env.KV.put("pending", "1", { expirationTtl: 3600 });
  await env.KV.put("lock:dispatch", "1", { expirationTtl: 60 });
  ctx.waitUntil(dispatch(env));
}

export async function flushPending(env) {
  if (!(await env.KV.get("pending")) || (await env.KV.get("lock:dispatch"))) return;
  await env.KV.delete("pending");
  await env.KV.put("lock:dispatch", "1", { expirationTtl: 60 });
  await dispatch(env);
}

export async function putItem(env, ctx, item) {
  const ts = Date.now();
  const key = `in:${ts}:${crypto.randomUUID().slice(0, 8)}`;
  await env.KV.put(key, JSON.stringify({ ...item, ts }), { expirationTtl: ITEM_TTL });
  await requestRun(env, ctx);
  return key;
}

export async function submit(req, env, ctx) {
  if (await limited(env.RL_SUBMIT, clientIp(req))) return fail("rate_limited", 429);
  const { body, err } = await readJson(req, 16_000);
  if (err) return fail(err, err === "too_large" ? 413 : 400);
  const draft = cleanDraft(body.draft);
  if (!draft) return fail("invalid_draft", 400);
  const orig = await verify(env.SIGNING_KEY, body.tok, TOKEN_AGE);
  const edited = orig ? CORE.some((k) => (orig.d[k] || null) !== (draft[k] || null)) : null;
  const key = await putItem(env, ctx, {
    channel: "app", type: "draft", text: null, media: [], sender: null, draft,
    orig: orig ? orig.d : null, conf: orig ? orig.c : null, edited, pv: orig ? orig.v : null,
  });
  return json({ ok: true, id: key.slice(3) });
}
