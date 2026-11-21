import { scrub } from "./draft.js";
import { putItem } from "./inbox.js";
import { sendReply } from "./reply.js";
import { byHandle, byText, loadSources, raiseStory } from "./stories.js";
import {
  clientIp, fail, graph, hmacHex, igInbox, json, limited, metaSignatureOk, parseSignedRequest, safeEqual, seal, senderHash, waActive,
} from "./util.js";

const STORY = new Set(["story", "ig_story", "story_mention"]);
const IMAGE = new Set(["image", "media", "ig_post", "share"]);
const DELETE = /^\s*(delete|slet)(\s+(my|mine)\s+data)?\s*[.!]?\s*$/i;
const MEDIA_REF = /^(https:\/\/|wa:)/;

const DELETED = "Dine data hos EventBorg er slettet. / Your EventBorg data has been deleted.";

// Instagram DMs (Instagram API with Facebook Login) → normalized messages; our own echoes are skipped.
export function igMessages(body) {
  if (body?.object !== "instagram") return [];
  const out = [];
  for (const entry of body.entry || []) {
    for (const m of entry.messaging || []) {
      const msg = m.message;
      if (!msg || msg.is_echo || msg.is_deleted || msg.is_unsupported || !m.sender?.id) continue;
      const atts = (msg.attachments || []).map((a) => ({ type: a.type, url: a.payload?.url, title: a.payload?.title }));
      out.push({ from: String(m.sender.id), mid: msg.mid, text: msg.text || "", atts, names: [] });
    }
  }
  return out;
}

export function waMessages(body, phoneId) {
  if (body?.object !== "whatsapp_business_account") return [];
  const out = [];
  for (const entry of body.entry || []) {
    for (const ch of entry.changes || []) {
      const v = ch.value || {};
      if (ch.field !== "messages" || (phoneId && v.metadata?.phone_number_id !== phoneId)) continue;
      const names = (v.contacts || []).map((c) => c.profile?.name).filter(Boolean);
      for (const m of v.messages || []) {
        const part = m[m.type] || {};
        const img = m.type === "image" || (m.type === "document" && /^image\//.test(part.mime_type || ""));
        const atts = img ? [{ type: "image", url: `wa:${part.id}` }] : m.type === "text" ? [] : [{ type: m.type }];
        out.push({ from: String(m.from), mid: m.id, text: m.text?.body || part.caption || "", atts, names });
      }
    }
  }
  return out;
}

// Public Instagram DMs/story mentions need App Review, so they stay off until IG_PUBLIC_INBOX=true; WhatsApp runs
// only once its token, phone number id and number exist. Everything else is acknowledged and dropped.
export function inbound(body, env) {
  return [
    ...(igInbox(env) ? igMessages(body).map((m) => ["ig_dm", m]) : []),
    ...(waActive(env) ? waMessages(body, env.WA_PHONE_ID).map((m) => ["wa", m]) : []),
  ];
}

// Stories go to story alerts and are never extracted; everything else with text or an image is an inbox item.
export function classify(msg) {
  const types = msg.atts.map((a) => a.type);
  const text = [msg.text, ...msg.atts.map((a) => a.title)].filter(Boolean).join("\n").slice(0, 4000);
  if (types.some((t) => STORY.has(t))) return { kind: "story", mention: types.includes("story_mention"), text };
  if (!msg.atts.length && DELETE.test(msg.text)) return { kind: "delete" };
  const media = msg.atts.filter((a) => IMAGE.has(a.type) && MEDIA_REF.test(a.url || "")).map((a) => a.url).slice(0, 3);
  if (!text.trim() && !media.length) return { kind: "skip" };
  return { kind: "item", type: types[0] || "text", text, media };
}

export function stripNames(text, names) {
  let t = text;
  for (const n of names) t = t.split(n).join("");
  return t;
}

async function sourceForIgUser(env, igsid, sender, sources) {
  const key = `igu:${sender}`;
  let sid = await env.KV.get(key);
  if (sid === null) {
    if (!env.META_TOKEN) return null;
    const r = await fetch(`${graph(env)}/${encodeURIComponent(igsid)}?fields=username&access_token=${encodeURIComponent(env.META_TOKEN)}`);
    if (!r.ok) return null;
    sid = byHandle(sources, (await r.json()).username)?.id || "-";
    await env.KV.put(key, sid, { expirationTtl: 7 * 86400 });
  }
  return sources.find((s) => s.id === sid) || null;
}

export async function deleteSenders(env, ctx, hashes) {
  const set = new Set(hashes);
  let cursor;
  do {
    const page = await env.KV.list({ prefix: "in:", cursor });
    for (const k of page.keys) if (set.has(k.metadata?.s)) await env.KV.delete(k.name);
    cursor = page.list_complete ? null : page.cursor;
  } while (cursor);
  for (const h of set) await env.KV.delete(`igu:${h}`);
  await putItem(env, ctx, { channel: "system", type: "delete", senders: [...set], text: null, media: [], sender: null }, { s: "-" });
}

async function handle(env, ctx, channel, msg) {
  const c = classify(msg);
  if (c.kind === "skip") return;
  const sender = await senderHash(env, channel, msg.from);
  if (await limited(env.RL_SENDER, sender)) return console.log(`sender rate limited (${channel})`);
  const h = { c: channel, to: msg.from, mid: msg.mid, ts: Date.now() };
  if (c.kind === "delete") {
    await deleteSenders(env, ctx, [sender]);
    return ctx.waitUntil(sendReply(env, h, DELETED));
  }
  if (c.kind === "story") {
    const sources = await loadSources(env);
    const src = (!c.mention && byText(sources, c.text)) || (channel === "ig_dm" ? await sourceForIgUser(env, msg.from, sender, sources) : null);
    if (src) await raiseStory(env, ctx, src);
    return;
  }
  const mh = (await hmacHex(env.SIGNING_KEY, `mid|${channel}|${msg.mid}`)).slice(0, 16);
  await putItem(env, ctx, {
    channel, type: c.type, text: scrub(stripNames(c.text, msg.names)) || null, media: c.media, sender, mh,
    rt: await seal(env.SIGNING_KEY, h),
  }, { s: sender });
}

async function settle(work) {
  for (const r of await Promise.allSettled(work)) if (r.status === "rejected") console.log(`inbox item: ${r.reason?.message}`);
}

export function metaVerify(url, env) {
  const p = url.searchParams;
  const ok = p.get("hub.mode") === "subscribe" && env.META_VERIFY_TOKEN && safeEqual(p.get("hub.verify_token") || "", env.META_VERIFY_TOKEN);
  return ok ? new Response(p.get("hub.challenge") || "", { headers: { "content-type": "text/plain" } }) : fail("forbidden", 403);
}

export async function metaWebhook(req, env, ctx) {
  if (await limited(env.RL_HOOK, clientIp(req))) return fail("rate_limited", 429);
  const raw = await req.arrayBuffer();
  if (raw.byteLength > 256000) return fail("too_large", 413);
  if (!(await metaSignatureOk(env.META_APP_SECRET, raw, req.headers.get("x-hub-signature-256")))) return fail("bad_signature", 401);
  let body;
  try {
    body = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    return fail("bad_request", 400);
  }
  await settle(inbound(body, env).map(([channel, m]) => handle(env, ctx, channel, m)));
  return json({ ok: true });
}

// Meta's data deletion callback: we only hold salted hashes, so delete whatever matches this user's ids.
export async function metaDeletion(req, env, ctx) {
  const form = await req.formData().catch(() => null);
  const data = await parseSignedRequest(env.META_APP_SECRET, form?.get("signed_request"));
  if (!data) return fail("bad_signature", 400);
  const id = String(data.user_id);
  await deleteSenders(env, ctx, [await senderHash(env, "ig_dm", id), await senderHash(env, "fb", id)]);
  const code = crypto.randomUUID().replace(/-/g, "").slice(0, 12);
  return json({ url: `${new URL(req.url).origin}/data-deletion?code=${code}`, confirmation_code: code });
}
