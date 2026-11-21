import assert from "node:assert/strict";
import { test } from "node:test";
import { cleanDraft, findUrl, fixYear } from "../src/draft.js";
import { robotsAllows } from "../src/robots.js";
import { createHmac } from "node:crypto";
import { classify, igMessages, inbound, stripNames, waMessages } from "../src/social.js";
import { byHandle, byText, eligible, storyMessage } from "../src/stories.js";
import { activeChannels, metaSignatureOk, parseSignedRequest, seal, sign, unseal, verify } from "../src/util.js";

const ROBOTS = `
# comment
User-agent: *
Disallow: /private
Allow: /private/events$
Disallow: /*.pdf$
Disallow: /tmp/

User-agent: BadBot
User-agent: EventBorgBot
Disallow: /admin
`;

test("robots: specific group replaces *", () => {
  assert.equal(robotsAllows(ROBOTS, "EventBorgBot", "/private"), true);
  assert.equal(robotsAllows(ROBOTS, "EventBorgBot", "/admin/x"), false);
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/admin/x"), true);
});

test("robots: longest match wins, allow wins ties, wildcards and anchors", () => {
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/private/x"), false);
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/private/events"), true);
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/private/events/1"), false);
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/files/a.pdf"), false);
  assert.equal(robotsAllows(ROBOTS, "OtherBot", "/files/a.pdf?x=1"), true);
  assert.equal(robotsAllows("User-agent: *\nDisallow: /a\nAllow: /a", "X", "/a"), true);
  assert.equal(robotsAllows("User-agent: *\nDisallow:", "X", "/"), true);
  assert.equal(robotsAllows("User-agent: *\nDisallow: /", "X", "/"), false);
  assert.equal(robotsAllows("", "X", "/"), true);
});

test("token: verifies, rejects tampering, expiry and junk", async () => {
  const now = Date.now();
  const tok = await sign("k1", { d: { title: "Jazz" }, c: 0.9, ts: now });
  assert.equal((await verify("k1", tok, 1000, now + 500)).c, 0.9);
  assert.equal(await verify("k2", tok, 1000, now), null);
  assert.equal(await verify("k1", tok, 1000, now + 2000), null);
  const [body, sig] = tok.split(".");
  const forged = Buffer.from(JSON.stringify({ d: { title: "Spam" }, c: 1, ts: now })).toString("base64url");
  assert.equal(await verify("k1", `${forged}.${sig}`, 1000, now), null);
  assert.equal(await verify("k1", `${body}.${sig}.x`, 1000, now), null);
  assert.equal(await verify("k1", "garbage", 1000, now), null);
  assert.equal(await verify("k1", undefined, 1000, now), null);
});

test("draft: keeps known fields, caps, scrubs contact details, needs title+start", () => {
  const d = cleanDraft({
    title: "  Koncert\u0007 i parken  ", start: "2026-12-04T20:00", end: "tomorrow", url: "javascript:alert(1)",
    description: "Ring 12 34 56 78 eller skriv til a@b.dk. " + "x".repeat(2000), category: "music", language: "fr",
    is_event: true, evil: "<script>",
  });
  assert.equal(d.title, "Koncert i parken");
  assert.equal(d.start, "2026-12-04T20:00");
  assert.equal(d.end, undefined);
  assert.equal(d.url, undefined);
  assert.equal(d.language, undefined);
  assert.equal(d.category, "music");
  assert.ok(!/12 34 56 78|a@b\.dk/.test(d.description) && d.description.length === 1500);
  assert.equal(d.evil, undefined);
  assert.equal(cleanDraft({ title: "x", start: "2026-12-04", description: "Ring 12345678. Pris 12345678.50" }).description, "Ring . Pris 12345678.50");
  assert.equal(cleanDraft({ title: "No date" }), null);
  assert.equal(cleanDraft({ title: "Bad date", start: "2026-13-45" }), null);
  assert.equal(cleanDraft({ title: "Day", start: "2026-12-04" }).start, "2026-12-04");
});

test("findUrl trims trailing punctuation", () => {
  assert.equal(findUrl("Se mere: https://sonderborghus.dk/event/jazz/)."), "https://sonderborghus.dk/event/jazz/");
  assert.equal(findUrl("no link here"), null);
});

test("fixYear rolls a year-less past date forward only when the text has no year", () => {
  const d = { title: "Jul", start: "2025-12-13T16:00", end: "2025-12-13T18:00" };
  assert.deepEqual(fixYear(d, "2026-11-21", "lørdag d. 13. december"), { title: "Jul", start: "2026-12-13T16:00", end: "2026-12-13T18:00" });
  assert.equal(fixYear(d, "2026-11-21", "13. december 2025").start, "2025-12-13T16:00");
  assert.equal(fixYear({ title: "x", start: "2026-11-20" }, "2026-11-21", "").start, "2026-11-20");
  assert.equal(fixYear({ title: "x", start: "2026-01-05" }, "2026-11-21", "").start, "2027-01-05");
});

test("meta webhook signature: valid, tampered, wrong secret, missing prefix", async () => {
  const raw = new TextEncoder().encode('{"object":"instagram","entry":[]}');
  const sig = "sha256=" + createHmac("sha256", "appsecret").update(raw).digest("hex");
  assert.equal(await metaSignatureOk("appsecret", raw, sig), true);
  assert.equal(await metaSignatureOk("appsecret", raw, sig.toUpperCase().replace("SHA256", "sha256")), true);
  assert.equal(await metaSignatureOk("other", raw, sig), false);
  assert.equal(await metaSignatureOk("appsecret", new TextEncoder().encode('{"object":"x"}'), sig), false);
  assert.equal(await metaSignatureOk("appsecret", raw, sig.slice(7)), false);
  assert.equal(await metaSignatureOk("", raw, sig), false);
  assert.equal(await metaSignatureOk("appsecret", raw, null), false);
});

test("meta deletion signed_request", async () => {
  const payload = Buffer.from(JSON.stringify({ algorithm: "HMAC-SHA256", user_id: "123", issued_at: 1 })).toString("base64url");
  const sig = createHmac("sha256", "appsecret").update(payload).digest("base64url");
  assert.equal((await parseSignedRequest("appsecret", `${sig}.${payload}`)).user_id, "123");
  assert.equal(await parseSignedRequest("other", `${sig}.${payload}`), null);
  assert.equal(await parseSignedRequest("appsecret", `${sig}.${payload}x`), null);
  assert.equal(await parseSignedRequest("appsecret", "junk"), null);
});

test("sealed reply handle opens only with the same key", async () => {
  const box = await seal("k1", { c: "wa", to: "4512345678", ts: 1 });
  assert.ok(!box.includes("4512345678"));
  assert.equal((await unseal("k1", box)).to, "4512345678");
  assert.equal(await unseal("k2", box), null);
  assert.equal(await unseal("k1", "x.y"), null);
});

const ig = (message, sender = "111") => ({ object: "instagram", entry: [{ id: "999", messaging: [{ sender: { id: sender }, recipient: { id: "999" }, message }] }] });

test("instagram: stories go to alerts, posts and images to the inbox, echoes are skipped", () => {
  const [mention] = igMessages(ig({ mid: "m1", attachments: [{ type: "story_mention", payload: { url: "https://lookaside.fbsbx.com/x" } }] }));
  assert.deepEqual({ ...classify(mention), text: undefined }, { kind: "story", mention: true, text: undefined });
  const [fwd] = igMessages(ig({ mid: "m2", text: "se @mejeriet", attachments: [{ type: "ig_story", payload: { url: "https://lookaside.fbsbx.com/y" } }] }));
  assert.equal(classify(fwd).kind, "story");
  assert.equal(classify(fwd).mention, false);
  const [post] = igMessages(ig({ mid: "m3", attachments: [{ type: "ig_post", payload: { url: "https://scontent.cdninstagram.com/p.jpg", title: "Koncert lørdag" } }] }));
  assert.deepEqual(classify(post), { kind: "item", type: "ig_post", text: "Koncert lørdag", media: ["https://scontent.cdninstagram.com/p.jpg"] });
  const [reel] = igMessages(ig({ mid: "m4", attachments: [{ type: "ig_reel", payload: { url: "https://scontent.cdninstagram.com/v.mp4", title: "Jazz i aften" } }] }));
  assert.deepEqual(classify(reel), { kind: "item", type: "ig_reel", text: "Jazz i aften", media: [] });
  const [del] = igMessages(ig({ mid: "m5", text: " SLET " }));
  assert.equal(classify(del).kind, "delete");
  const [noText] = igMessages(ig({ mid: "m6", attachments: [{ type: "audio", payload: { url: "https://x.fbcdn.net/a" } }] }));
  assert.equal(classify(noText).kind, "skip");
  assert.equal(igMessages(ig({ mid: "m7", text: "hi", is_echo: true })).length, 0);
  assert.equal(igMessages({ object: "page", entry: [] }).length, 0);
});

test("whatsapp: captions, image refs, phone-number filter, names removed", () => {
  const body = { object: "whatsapp_business_account", entry: [{ changes: [{ field: "messages", value: {
    metadata: { phone_number_id: "PN1" }, contacts: [{ profile: { name: "Anna Jensen" }, wa_id: "4511" }],
    messages: [
      { from: "4511", id: "w1", type: "image", image: { id: "MEDIA1", caption: "Fra Anna Jensen: loppemarked søndag" } },
      { from: "4511", id: "w2", type: "text", text: { body: "Koncert fredag kl 20" } },
      { from: "4511", id: "w3", type: "sticker", sticker: { id: "S" } },
    ] } }] }] };
  const msgs = waMessages(body, "PN1");
  assert.equal(msgs.length, 3);
  assert.deepEqual(classify(msgs[0]).media, ["wa:MEDIA1"]);
  assert.equal(stripNames(classify(msgs[0]).text, msgs[0].names), "Fra : loppemarked søndag");
  assert.equal(classify(msgs[1]).type, "text");
  assert.equal(classify(msgs[2]).kind, "skip");
  assert.equal(waMessages(body, "OTHER").length, 0);
});

test("channel gates: public Instagram inbox behind IG_PUBLIC_INBOX, WhatsApp needs token + phone id + number", () => {
  const igBody = ig({ mid: "g1", text: "Koncert fredag" });
  const waBody = { object: "whatsapp_business_account", entry: [{ changes: [{ field: "messages", value: {
    metadata: { phone_number_id: "PN1" }, messages: [{ from: "4511", id: "w1", type: "text", text: { body: "Loppemarked" } }] } }] }] };
  const wa = { WA_TOKEN: "t", WA_PHONE_ID: "PN1", WA_NUMBER: "+45 11 22 33 44" };
  assert.deepEqual(inbound(igBody, {}), []);
  assert.deepEqual(inbound(igBody, { IG_PUBLIC_INBOX: "false" }), []);
  assert.equal(inbound(igBody, { IG_PUBLIC_INBOX: "true" })[0][0], "ig_dm");
  assert.deepEqual(inbound(waBody, { WA_TOKEN: "t", WA_PHONE_ID: "PN1" }), []);
  assert.equal(inbound(waBody, wa)[0][0], "wa");
  assert.deepEqual(activeChannels({ IG_HANDLE: "eventborg", IG_PUBLIC_INBOX: "false" }), { ig: null, wa: null });
  assert.deepEqual(activeChannels({ IG_HANDLE: "eventborg", IG_PUBLIC_INBOX: "true", ...wa }), { ig: "eventborg", wa: "+4511223344" });
  assert.equal(classify({ text: "/slet", atts: [] }).kind, "item");
});

const SOURCES = [
  { id: "mejeriet", n: "Mejeriet", kind: "venue", ig: "mejerietsonderborg", names: ["Mejeriet"] },
  { id: "koncertsalen-alsion", n: "Koncertsalen Alsion", kind: "venue", fb: "https://www.facebook.com/alsion", names: ["Koncertsalen Alsion", "Alsion"] },
  { id: "sonderborghus", n: "Sønderborghus", kind: "venue", ig: "sonderborghus", names: ["Sønderborghus"] },
  { id: "tm", n: "Ticketmaster", kind: "api", ig: "ticketmaster", names: ["Ticketmaster"] },
  { id: "nolink", n: "Ukendt Sted", kind: "venue", names: ["Ukendt Sted"] },
];

test("story matcher: handles, names on word boundaries, folding, eligibility", () => {
  assert.equal(byHandle(SOURCES, "MejerietSonderborg")?.id, "mejeriet");
  assert.equal(byHandle(SOURCES, "@sonderborghus")?.id, "sonderborghus");
  assert.equal(byHandle(SOURCES, "ticketmaster"), null);
  assert.equal(byHandle(SOURCES, ""), null);
  assert.equal(byText(SOURCES, "Se Sonderborghus' story!")?.id, "sonderborghus");
  assert.equal(byText(SOURCES, "i aften på ALSION")?.id, "koncertsalen-alsion");
  assert.equal(byText(SOURCES, "Koncertsalen Alsion og Mejeriet")?.id, "koncertsalen-alsion");
  assert.equal(byText(SOURCES, "alsionfestival"), null);
  assert.equal(byText(SOURCES, "Ukendt Sted"), null);
  assert.equal(byText(SOURCES, "ticketmaster"), null);
  assert.equal(eligible(SOURCES[1]), true);
  const msg = storyMessage({ sourceId: "mejeriet", name: "Mejeriet", url: "https://www.instagram.com/stories/mejerietsonderborg/", ts: 5 });
  assert.equal(msg.condition, "'st_all' in topics || 'st_mejeriet' in topics");
  assert.equal(msg.data.ts, "5");
});
