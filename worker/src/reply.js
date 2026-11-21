import { graph, igInbox, waActive } from "./util.js";

const WINDOW = 23.5 * 3600 * 1000;
const WA_CAP = 900;

const post = (url, body, headers = {}) =>
  fetch(url, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });

async function waCapReached(env) {
  const key = `wa:n:${new Date().toISOString().slice(0, 7)}`;
  const n = Number(await env.KV.get(key)) || 0;
  return { full: n >= WA_CAP, bump: () => env.KV.put(key, String(n + 1), { expirationTtl: 40 * 86400 }) };
}

// One reply in the channel the item came from, only inside the 24 h window. Returns null or a short reason.
export async function sendReply(env, h, text) {
  if (!h || !h.to) return "no_handle";
  if (Date.now() - (h.ts || 0) > WINDOW) return "window_closed";
  const body = String(text).slice(0, 400);
  let r;
  if (h.c === "ig_dm") {
    if (!igInbox(env) || !env.META_TOKEN) return "inactive";
    r = await post(`${graph(env)}/me/messages?access_token=${encodeURIComponent(env.META_TOKEN)}`, { recipient: { id: h.to }, message: { text: body } });
  } else if (h.c === "wa") {
    if (!waActive(env)) return "inactive";
    const cap = await waCapReached(env);
    if (cap.full) return "wa_cap";
    r = await post(`${graph(env)}/${env.WA_PHONE_ID}/messages`, {
      messaging_product: "whatsapp", recipient_type: "individual", to: h.to, type: "text",
      text: { body, preview_url: true }, ...(h.mid && { context: { message_id: h.mid } }),
    }, { authorization: `Bearer ${env.WA_TOKEN}` });
    if (r.ok) await cap.bump();
  } else {
    return "no_channel";
  }
  return r.ok ? null : `HTTP ${r.status}`;
}
