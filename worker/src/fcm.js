import { b64u } from "./util.js";

const enc = new TextEncoder();
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const TOKEN_URI = "https://oauth2.googleapis.com/token";

const der = (pem) => Uint8Array.from(atob(pem.replace(/-----[^-]+-----|\s/g, "")), (c) => c.charCodeAt(0));
const part = (o) => b64u(enc.encode(JSON.stringify(o)));

// OAuth access token for FCM HTTP v1, signed with the service account key and cached in KV for ~55 min.
async function accessToken(env) {
  const cached = await env.KV.get("fcm:tok");
  if (cached) return cached;
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const aud = sa.token_uri || TOKEN_URI;
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${part({ alg: "RS256", typ: "JWT" })}.${part({ iss: sa.client_email, scope: SCOPE, aud, iat: now, exp: now + 3600 })}`;
  const key = await crypto.subtle.importKey("pkcs8", der(sa.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(unsigned)));
  const r = await fetch(aud, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${unsigned}.${b64u(sig)}` }),
  });
  if (!r.ok) throw new Error(`oauth HTTP ${r.status}`);
  const { access_token: tok, expires_in: ttl } = await r.json();
  await env.KV.put("fcm:tok", tok, { expirationTtl: Math.max(60, Math.min(3300, (ttl || 3600) - 300)) });
  return tok;
}

export async function sendFcm(env, message) {
  if (!env.FCM_SERVICE_ACCOUNT) return console.log("fcm skipped: no FCM_SERVICE_ACCOUNT");
  const sa = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  const post = async () =>
    fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { authorization: `Bearer ${await accessToken(env)}`, "content-type": "application/json" },
      body: JSON.stringify({ message }),
    });
  let r = await post();
  if (r.status === 401) {
    await env.KV.delete("fcm:tok");
    r = await post();
  }
  if (!r.ok) console.log(`fcm failed: HTTP ${r.status}`);
  return r.ok;
}
