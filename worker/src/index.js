import { extract } from "./extract.js";
import { flushPending, submit } from "./inbox.js";
import { renderPage } from "./pages.js";
import { fail } from "./util.js";

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    try {
      if (req.method === "POST" && path === "/extract") return await extract(req, env);
      if (req.method === "POST" && path === "/submit") return await submit(req, env, ctx);
      if (req.method === "GET" || req.method === "HEAD") {
        const page = renderPage(path, url, req, env);
        if (page) return page;
      }
      return fail("not_found", 404);
    } catch (e) {
      console.log(`error ${path}: ${e?.message}`);
      return fail("server", 500);
    }
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(flushPending(env));
  },
};
