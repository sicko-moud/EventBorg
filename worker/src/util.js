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

const b64u = (bytes) => {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
const unb64u = (s) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));

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
