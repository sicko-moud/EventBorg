import { extract } from "./extract.js";
import { flushPending, submit, suggestSource } from "./inbox.js";
import { job } from "./job.js";
import { renderPage } from "./pages.js";
import { metaDeletion, metaVerify, metaWebhook } from "./social.js";
import { listStories, storySeen } from "./stories.js";
import { activeChannels, fail, json } from "./util.js";

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const m = req.method;
    try {
      if (m === "POST" && path === "/extract") return await extract(req, env);
      if (m === "POST" && path === "/submit") return await submit(req, env, ctx);
      if (m === "POST" && path === "/suggest") return await suggestSource(req, env, ctx);
      if (m === "POST" && path === "/story-seen") return await storySeen(req, env, ctx);
      if (m === "GET" && path === "/stories") return await listStories(env);
      if (m === "GET" && path === "/channels") return json(activeChannels(env), 200, { "cache-control": "public, max-age=3600" });
      if (m === "GET" && path === "/meta/webhook") return metaVerify(url, env);
      if (m === "POST" && path === "/meta/webhook") return await metaWebhook(req, env, ctx);
      if (m === "POST" && path === "/meta/deletion") return await metaDeletion(req, env, ctx);
      if (path.startsWith("/job/")) return await job(req, env, path, url);
      if (m === "GET" || m === "HEAD") {
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
