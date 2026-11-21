const enc = new TextEncoder();

export const json = (body, status = 200, headers = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });

export const fail = (err, status) => json({ ok: false, err }, status);

export async function readJson(req, maxBytes) {
  const len = Number(req.headers.get("content-length") || 0);
  if (len > maxBytes) return { err: "too_large" };
  const buf = await req.arrayBuffer();
  if (buf.byteLength > maxBytes) return { err: "too_large" };
  try {
    const body = JSON.parse(new TextDecoder().decode(buf));
    return body && typeof body === "object" && !Array.isArray(body) ? { body } : { err: "bad_request" };
  } catch {
    return { err: "bad_request" };
  }
}

// Counters live in Cloudflare's rate-limit binding only; nothing about the caller is stored by us.
export async function limited(binding, key) {
  if (!binding) return false;
  const { success } = await binding.limit({ key });
  return !success;
}

export const clientIp = (req) => req.headers.get("cf-connecting-ip") || "local";

export const userAgent = (env) => `EventBorgBot/1.0 (+https://github.com/${env.GH_REPO})`;

export const b64u = (bytes) => {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
export const unb64u = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

const hmacKey = (secret) =>
  crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);

export async function sign(secret, obj) {
  const body = b64u(enc.encode(JSON.stringify(obj)));
  const sig = await crypto.subtle.sign("HMAC", await hmacKey(secret), enc.encode(body));
  return `${body}.${b64u(new Uint8Array(sig))}`;
}

export async function verify(secret, tok, maxAgeMs, now = Date.now()) {
  if (!secret || typeof tok !== "string" || tok.length > 24000) return null;
  const [body, sig, extra] = tok.split(".");
  if (!body || !sig || extra !== undefined) return null;
  try {
    if (!(await crypto.subtle.verify("HMAC", await hmacKey(secret), unb64u(sig), enc.encode(body)))) return null;
    const obj = JSON.parse(new TextDecoder().decode(unb64u(body)));
    return typeof obj.ts === "number" && now - obj.ts <= maxAgeMs && obj.ts <= now + 60000 ? obj : null;
  } catch {
    return null;
  }
}

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
const bytes = (d) => (typeof d === "string" ? enc.encode(d) : d);

export async function hmacHex(secret, data) {
  return hex(await crypto.subtle.sign("HMAC", await hmacKey(secret), bytes(data)));
}

export async function hmacB64u(secret, data) {
  return b64u(new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret), bytes(data))));
}

export function safeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

// Meta signs every webhook body with the app secret: X-Hub-Signature-256: sha256=<hex HMAC of the raw bytes>.
export async function metaSignatureOk(appSecret, raw, header) {
  if (!appSecret || typeof header !== "string" || !header.startsWith("sha256=")) return false;
  return safeEqual(await hmacHex(appSecret, raw), header.slice(7).toLowerCase());
}

// Facebook's data deletion callback: signed_request = <b64u HMAC-SHA256(payload)>.<b64u JSON payload>.
export async function parseSignedRequest(appSecret, sr) {
  const [sig, payload, extra] = String(sr || "").split(".");
  if (!appSecret || !sig || !payload || extra !== undefined) return null;
  if (!safeEqual(sig.replace(/=+$/, ""), await hmacB64u(appSecret, payload))) return null;
  try {
    const data = JSON.parse(new TextDecoder().decode(unb64u(payload)));
    return String(data.algorithm).toUpperCase() === "HMAC-SHA256" && data.user_id ? data : null;
  } catch {
    return null;
  }
}

// Sender ids are only ever stored as this keyed hash.
export const senderHash = async (env, channel, id) => (await hmacHex(env.SIGNING_KEY, `sender|${channel}|${id}`)).slice(0, 20);

const aesKey = async (secret) =>
  crypto.subtle.importKey("raw", await crypto.subtle.digest("SHA-256", enc.encode(`reply|${secret}`)), "AES-GCM", false, ["encrypt", "decrypt"]);

// The address we reply to travels only inside this AES-GCM box, which only the Worker can open.
export async function seal(secret, obj) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await aesKey(secret), enc.encode(JSON.stringify(obj)));
  return `${b64u(iv)}.${b64u(new Uint8Array(ct))}`;
}

export async function unseal(secret, tok) {
  try {
    const [iv, ct] = String(tok).split(".");
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64u(iv) }, await aesKey(secret), unb64u(ct));
    return JSON.parse(new TextDecoder().decode(pt));
  } catch {
    return null;
  }
}

export const bearerOk = (req, token) => Boolean(token) && safeEqual(req.headers.get("authorization") || "", `Bearer ${token}`);

const FOLD = { ø: "o", æ: "ae", å: "a", ß: "ss" };
export const fold = (s) =>
  String(s || "").toLowerCase().replace(/[øæåß]/g, (c) => FOLD[c]).replace(/aa/g, "a").normalize("NFKD").replace(/[̀-ͯ]/g, "");

export const graph = (env) => `https://graph.facebook.com/${env.GRAPH_VERSION || "v27.0"}`;

export const igInbox = (env) => env.IG_PUBLIC_INBOX === "true";
export const waActive = (env) => Boolean(env.WA_TOKEN && env.WA_PHONE_ID && env.WA_NUMBER);

// The ways in that are live right now; the app's "Add your event" screen and the static pages show only these.
export function activeChannels(env) {
  const wa = waActive(env) ? String(env.WA_NUMBER).replace(/\D/g, "") : "";
  return { ig: igInbox(env) && env.IG_HANDLE ? env.IG_HANDLE : null, wa: wa ? `+${wa}` : null };
}
