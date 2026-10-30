// Shared by the background hooks (prompt hint, team-first gate): search the team's Prior Art for a piece of text and
// format the strong hits as short lines for the agent's context. Silent on any problem: hooks must never get in the way.
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { homedir } from "node:os";
import { join, dirname } from "node:path";

const AIMED = "contains instructions aimed at AI agents";

export function config() {
  let cfg = {};
  try { cfg = JSON.parse(readFileSync(join(homedir(), ".priorart", "config.json"), "utf8")); } catch {}
  return {
    // Only the configured server (never a repo's .priorart.json, never a default): with none, the hooks stay silent.
    serverUrl: process.env.PRIORART_URL || cfg.serverUrl || null,
    author: process.env.PRIORART_AUTHOR || cfg.author || process.env.USER || "",
    scope: process.env.PRIORART_SCOPE || cfg.scope || "app",
    token: process.env.PRIORART_TOKEN || cfg.token || null,
    commonsUrl: process.env.PRIORART_COMMONS_URL || cfg.commonsUrl || null,
  };
}

/**
 * Strong hits for `text` as context lines, or [] when there are none (or no server, or it's slow).
 * self: the caller's session, never matched. seenKey: remembers what this session was already told, so it isn't repeated.
 */
export async function teamHints(text, { self = "", seenKey = null, timeoutMs = 3000 } = {}) {
  const { serverUrl, author, scope, token } = config();
  if (!serverUrl || text.trim().length < 15) return [];
  let r;
  try {
    const res = await fetch(`${serverUrl}/api/search?q=${encodeURIComponent(text.slice(0, 500))}&limit=3&user=${encodeURIComponent(author)}&scope=${scope}${self ? `&excludeSession=${encodeURIComponent(self)}` : ""}`,
      { signal: AbortSignal.timeout(timeoutMs), headers: token ? { authorization: `Bearer ${token}` } : {} });
    if (!res.ok) return [];
    r = await res.json();
  } catch { return []; }
  // Speak up only when a hit shares enough of the text's meaningful terms: a weak match is worse than silence. Need half
  // of the first 6 meaningful terms and never fewer than 2: a single shared word ("big", "deploy") says nothing.
  const t = r.queryTerms ?? 0;
  if (t < 2) return [];
  const need = Number(process.env.PRIORART_HOOK_MIN_TERMS) || Math.max(2, Math.ceil(Math.min(t, 6) / 2));
  let records = r.records.filter((h) => (h.matchedTerms ?? 0) >= need);
  let sessions = r.sessions.filter((h) => (h.matchedTerms ?? 0) >= need);
  const seenFile = seenKey && join(homedir(), ".priorart", "state", `hint-${String(seenKey).replace(/[^A-Za-z0-9_.-]/g, "_")}.json`);
  let seen = [];
  if (seenFile) {
    try { seen = JSON.parse(readFileSync(seenFile, "utf8")); } catch {}
    records = records.filter((h) => !seen.includes(h.id));
    sessions = sessions.filter((h) => !seen.includes(h.id));
  }
  const cut = (x, n) => { const s = (x || "").replace(/\s+/g, " ").trim(); if (s.length <= n) return s; const c = s.slice(0, n); return c.slice(0, Math.max(c.lastIndexOf(" "), n - 20)) + "…"; };
  const day = (x) => (x ? new Date(x).toLocaleDateString("sv") : "");   // local date, same as the CLI
  // A hit whose text addresses AI agents is mentioned by id only: its text never goes into this agent's context.
  const show = (h, text) => h.warnings?.includes(AIMED)
    ? ` — ⚠ text withheld: it contains instructions aimed at AI agents (read it with get only if the user asks)`
    : `${h.warnings?.length ? ` ⚠ ${h.warnings.join("; ")} —` : ""}${text}`;
  const lines = [];
  for (const h of records) lines.push(`- [record ${h.status}] "${h.warnings?.includes(AIMED) ? "(withheld)" : h.title}" by ${h.author}${h.team ? ` (${h.team})` : ""} (${day(h.createdAt)}) id=${h.id}${show(h, h.decision ? ` — ${cut(h.decision, 220)}` : "")}`);
  for (const h of sessions.slice(0, 2)) lines.push(`- [session] ${h.author} on ${day(h.endedAt)} in ${h.cwd}: id=${h.id}${show(h, ` …${cut(h.snippet?.replace(/<\/?b>/g, ""), 220)}…`)}`);
  if (lines.length && seenFile) {
    try { mkdirSync(dirname(seenFile), { recursive: true }); writeFileSync(seenFile, JSON.stringify([...seen, ...records.map((h) => h.id), ...sessions.map((h) => h.id)].slice(-200))); } catch {}
  }
  return lines;
}
