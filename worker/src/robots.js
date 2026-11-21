// RFC 9309 subset: groups, Allow/Disallow, longest match wins, Allow wins ties, `*` and `$`.
export function parseRobots(txt) {
  const groups = [];
  let cur = null;
  let agentLine = false;
  for (const raw of String(txt || "").split(/\r?\n/)) {
    const line = raw.replace(/#.*/, "").trim();
    const i = line.indexOf(":");
    if (i < 1) continue;
    const k = line.slice(0, i).trim().toLowerCase();
    const v = line.slice(i + 1).trim();
    if (k === "user-agent") {
      if (!cur || !agentLine) groups.push((cur = { agents: [], rules: [] }));
      cur.agents.push(v.toLowerCase());
      agentLine = true;
    } else {
      agentLine = false;
      if (cur && (k === "allow" || k === "disallow") && v) cur.rules.push({ allow: k === "allow", path: v });
    }
  }
  return groups;
}

function matches(pattern, path) {
  const anchored = pattern.endsWith("$");
  const body = (anchored ? pattern.slice(0, -1) : pattern)
    .split("*")
    .map((s) => s.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
    .join(".*");
  return new RegExp("^" + body + (anchored ? "$" : "")).test(path);
}

export function robotsAllows(txt, token, path) {
  const groups = parseRobots(txt);
  const t = token.toLowerCase();
  const mine = groups.filter((g) => g.agents.includes(t));
  const rules = (mine.length ? mine : groups.filter((g) => g.agents.includes("*"))).flatMap((g) => g.rules);
  let best = null;
  for (const r of rules) {
    if (!matches(r.path, path)) continue;
    if (!best || r.path.length > best.len || (r.path.length === best.len && r.allow)) best = { len: r.path.length, allow: r.allow };
  }
  return !best || best.allow;
}
