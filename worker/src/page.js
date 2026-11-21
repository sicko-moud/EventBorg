import { robotsAllows } from "./robots.js";
import { userAgent } from "./util.js";

const TOKEN = "EventBorgBot";
const MAX_HTML = 1_500_000;
const MAX_TEXT = 10_000;
const SKIP = "script,style,noscript,svg,template,iframe,nav,footer,header,form,select,button";
const BLOCK = "p,div,br,li,h1,h2,h3,h4,h5,h6,tr,section,article,time,dd,dt";

async function allowed(u, ua) {
  try {
    const r = await fetch(`${u.origin}/robots.txt`, {
      headers: { "user-agent": ua },
      signal: AbortSignal.timeout(6000),
      cf: { cacheTtl: 3600, cacheEverything: true },
    });
    if (r.status >= 500) return false;
    if (r.status >= 400) return true;
    return robotsAllows((await r.text()).slice(0, 200_000), TOKEN, u.pathname + u.search);
  } catch {
    return false;
  }
}

async function pageText(res) {
  const out = [];
  const ld = [];
  const meta = [];
  let title = "";
  let skip = 0;
  let size = 0;
  const push = (s) => {
    if (size < MAX_TEXT) {
      out.push(s);
      size += s.length;
    }
  };
  await new HTMLRewriter()
    .on("title", { text: (t) => void (title += t.text) })
    .on("meta", {
      element(e) {
        const k = e.getAttribute("property") || e.getAttribute("name") || "";
        if (/^(og:title|og:description|description)$/i.test(k)) meta.push(e.getAttribute("content") || "");
      },
    })
    .on('script[type="application/ld+json"]', { text: (t) => void ld.push(t.text) })
    .on(SKIP, {
      element(e) {
        skip++;
        e.onEndTag(() => void skip--);
      },
    })
    .on(BLOCK, { element: () => push("\n") })
    .on("body", { text: (t) => void (skip === 0 && push(t.text)) })
    .transform(res)
    .arrayBuffer();
  const body = out.join("").replace(/[ \t ]+/g, " ").replace(/\s*\n\s*/g, "\n").trim();
  const json = ld.join("").trim().slice(0, 4000);
  return [title.trim(), ...meta, json && `JSON-LD: ${json}`, body.slice(0, MAX_TEXT)].filter(Boolean).join("\n");
}

// Reads a shared link's public page: robots.txt is checked on every hop, login walls and non-HTML are skipped.
export async function readPage(href, env) {
  const ua = userAgent(env);
  let u = new URL(href);
  for (let hop = 0; hop < 4; hop++) {
    if (!/^https?:$/.test(u.protocol)) return { err: "bad_url" };
    if (!(await allowed(u, ua))) return { err: "blocked" };
    const r = await fetch(u, {
      headers: { "user-agent": ua, accept: "text/html,application/xhtml+xml", "accept-language": "da,en;q=0.8" },
      redirect: "manual",
      signal: AbortSignal.timeout(10000),
    });
    if (r.status >= 300 && r.status < 400 && r.headers.get("location")) {
      u = new URL(r.headers.get("location"), u);
      continue;
    }
    const type = r.headers.get("content-type") || "";
    if (!r.ok || !/html/i.test(type) || Number(r.headers.get("content-length") || 0) > MAX_HTML) return { err: "unreadable" };
    if (/\/(login|signin|accounts\/login)/i.test(u.pathname)) return { err: "blocked" };
    return { text: await pageText(r), url: u.href };
  }
  return { err: "unreadable" };
}
