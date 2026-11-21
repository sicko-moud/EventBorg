import spec from "../../shared/extract.json" with { type: "json" };

export const CATEGORIES = spec.schema.properties.category.enum;
const CAPS = {
  title: 200, venue: 120, address: 200, town: 60, price: 120, organizer: 120, age: 40,
  description: 1500, summary_en: 200, summary_da: 200,
};
const DATE = /^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2})?$/;
const EMAIL = /[\w.+-]+@[\w-]+\.[\w.-]+/g;
const PHONE = /(?:\+45\s?)?(?<![\d.,:/-])(?:\d{2} \d{2} \d{2} \d{2}|\d{4} \d{4}|\d{8})(?![\d.,:/-])/g;

const text = (v, max) =>
  typeof v === "string"
    ? v.replace(/[\u0000-\u0008\u000b-\u001f\u007f]/g, "").replace(EMAIL, "").replace(PHONE, "").replace(/[ \t]{2,}/g, " ").trim().slice(0, max) || null
    : null;

function httpUrl(v) {
  if (typeof v !== "string" || v.length > 500) return null;
  try {
    const u = new URL(v.trim());
    return /^https?:$/.test(u.protocol) ? u.href : null;
  } catch {
    return null;
  }
}

// Keeps only known fields, caps lengths, strips e-mails/phones; used on LLM output and on client input.
export function cleanDraft(d) {
  if (!d || typeof d !== "object") return null;
  const out = {};
  for (const [k, max] of Object.entries(CAPS)) {
    const v = text(d[k], max);
    if (v) out[k] = v;
  }
  for (const k of ["start", "end"]) {
    const v = typeof d[k] === "string" ? d[k].trim().slice(0, 16) : "";
    if (DATE.test(v) && !Number.isNaN(Date.parse(v.length === 10 ? v : v + ":00Z"))) out[k] = v;
  }
  const url = httpUrl(d.url);
  if (url) out.url = url;
  if (["da", "en", "de"].includes(d.language)) out.language = d.language;
  if (CATEGORIES.includes(d.category)) out.category = d.category;
  return out.title && out.start ? out : null;
}

const shiftYear = (s, n) => (s ? String(Number(s.slice(0, 4)) + n) + s.slice(4) : s);

// Models often put a year-less date ("lørdag d. 13. december") in the past; move it to the next occurrence
// unless the shared text itself names that year.
export function fixYear(d, today, haystack) {
  if (!d?.start) return d;
  const y = new Date(today + "T00:00:00Z");
  y.setUTCDate(y.getUTCDate() - 1);
  const floor = y.toISOString().slice(0, 10);
  if (d.start.slice(0, 10) >= floor || String(haystack || "").includes(d.start.slice(0, 4))) return d;
  for (let n = 1; n <= 2; n++) {
    if (shiftYear(d.start, n).slice(0, 10) >= floor) return { ...d, start: shiftYear(d.start, n), ...(d.end && { end: shiftYear(d.end, n) }) };
  }
  return d;
}

export function findUrl(s) {
  const m = String(s || "").match(/https?:\/\/[^\s<>"']+/);
  return m ? httpUrl(m[0].replace(/[),.;!?]+$/, "")) : null;
}

export function buildPrompt(today, parts) {
  return spec.prompt.replace("{today}", today).replace("{text}", parts.filter(Boolean).join("\n\n") || "(see the image)");
}

export const SCHEMA = spec.schema;
export const PROMPT_VERSION = spec.version;
