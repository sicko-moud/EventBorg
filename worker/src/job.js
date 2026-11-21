import { sendReply } from "./reply.js";
import { bearerOk, fail, graph, json, readJson, unseal, waActive } from "./util.js";

// Endpoints for the GitHub inbox job only (Bearer INBOX_TOKEN). Social tokens never leave the Worker;
// media bytes are streamed through and never stored.
const MAX_MEDIA = 8_000_000;
const CDN = /^https:\/\/[^/?#]+\.(fbcdn\.net|cdninstagram\.com|fbsbx\.com)\//;

async function pull(env, url) {
  const limit = Math.min(50, Number(url.searchParams.get("limit")) || 50);
  const page = await env.KV.list({ prefix: "in:", limit });
  const items = [];
  for (const k of page.keys) {
    const v = await env.KV.get(k.name, "json");
    if (!v) continue;
    const { rt, ...item } = v;
    items.push({ key: k.name, ...item, replyable: Boolean(rt) });
  }
  return json({ ok: true, items, more: !page.list_complete });
}

async function resolveMedia(env, ref) {
  if (ref.startsWith("wa:")) {
    if (!waActive(env)) return null;
    const auth = { authorization: `Bearer ${env.WA_TOKEN}` };
    const meta = await fetch(`${graph(env)}/${encodeURIComponent(ref.slice(3))}`, { headers: auth });
    if (!meta.ok) return null;
    const m = await meta.json();
    return { res: await fetch(m.url, { headers: auth }), type: m.mime_type };
  }
  return CDN.test(ref) ? { res: await fetch(ref) } : null;
}

async function media(env, url) {
  const item = await env.KV.get(url.searchParams.get("k") || "-", "json");
  const ref = item?.media?.[Number(url.searchParams.get("i"))];
  if (typeof ref !== "string") return fail("not_found", 404);
  const got = await resolveMedia(env, ref);
  if (!got?.res.ok) return fail("upstream", 502);
  const type = (got.type || got.res.headers.get("content-type") || "").split(";")[0];
  if (!/^image\/(jpeg|png|webp|heic|heif|gif)$/.test(type)) return fail("not_image", 415);
  if (Number(got.res.headers.get("content-length") || 0) > MAX_MEDIA) return fail("too_large", 413);
  return new Response(got.res.body, { headers: { "content-type": type, "cache-control": "no-store" } });
}

// Deletes processed items and sends at most one reply each, using the sealed handle kept in KV.
async function done(req, env) {
  const { body, err } = await readJson(req, 64000);
  if (err) return fail(err, 400);
  const items = Array.isArray(body.items) ? body.items.slice(0, 20) : [];
  let deleted = 0, replied = 0;
  const errors = [];
  for (const it of items) {
    if (typeof it?.key !== "string" || !it.key.startsWith("in:")) continue;
    if (typeof it.reply === "string" && it.reply) {
      const stored = await env.KV.get(it.key, "json");
      const h = stored?.rt ? await unseal(env.SIGNING_KEY, stored.rt) : null;
      const e = h ? await sendReply(env, h, it.reply) : "no_handle";
      if (e) errors.push(e);
      else replied++;
    }
    await env.KV.delete(it.key);
    deleted++;
  }
  return json({ ok: true, deleted, replied, errors });
}

async function igDiscovery(env, url) {
  const u = url.searchParams.get("u") || "";
  if (!/^[A-Za-z0-9._]{1,30}$/.test(u)) return fail("bad_request", 400);
  if (!env.IG_USER_ID || !env.META_TOKEN) return fail("not_configured", 503);
  const n = Math.min(20, Number(url.searchParams.get("n")) || 10);
  const fields = `business_discovery.username(${u}){username,media.limit(${n}){id,caption,timestamp,permalink,media_type}}`;
  const r = await fetch(`${graph(env)}/${env.IG_USER_ID}?fields=${encodeURIComponent(fields)}&access_token=${encodeURIComponent(env.META_TOKEN)}`);
  const headers = { "content-type": "application/json", "cache-control": "no-store" };
  for (const h of ["x-app-usage", "x-business-use-case-usage"]) if (r.headers.get(h)) headers[h] = r.headers.get(h);
  return new Response(r.body, { status: r.status, headers });
}

export async function job(req, env, path, url) {
  if (!bearerOk(req, env.INBOX_TOKEN)) return fail("forbidden", 403);
  if (req.method === "GET" && path === "/job/inbox") return pull(env, url);
  if (req.method === "GET" && path === "/job/media") return media(env, url);
  if (req.method === "POST" && path === "/job/done") return done(req, env);
  if (req.method === "GET" && path === "/job/ig") return igDiscovery(env, url);
  return fail("not_found", 404);
}
