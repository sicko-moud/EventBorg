import assert from "node:assert/strict";
import { test } from "node:test";
import { cleanDraft, findUrl, fixYear } from "../src/draft.js";
import { robotsAllows } from "../src/robots.js";
import { sign, verify } from "../src/util.js";

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
