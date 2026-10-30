#!/usr/bin/env node
// Prior Art MCP server (stdio). Two tools: priorart_search, priorart_get. Any MCP-capable agent (Copilot CLI, Claude Code, Codex,
// Cursor) gets "has a teammate already done this?" through this one file.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

let cfg = {};
try { cfg = JSON.parse(readFileSync(join(homedir(), ".priorart", "config.json"), "utf8")); } catch {}
const BASE = process.env.PRIORART_URL || cfg.serverUrl || "http://localhost:8080";
const AUTHOR = process.env.PRIORART_AUTHOR || cfg.author || process.env.USER || "";
const TOKEN = process.env.PRIORART_TOKEN || cfg.token || null;
const get = (url) => fetch(url, { headers: TOKEN ? { authorization: `Bearer ${TOKEN}` } : {} });

const server = new McpServer({ name: "priorart", version: "0.1.0" });

server.tool("priorart_search",
  "Search the team priorart for prior investigations, findings and sessions. Call this BEFORE starting a task to check whether a teammate already did the work.",
  { query: z.string().describe("Plain-language description of the task or symptom"),
    limit: z.number().optional(),
    scope: z.enum(["team", "app", "all"]).optional().describe("team = my teams, app = my applications (default), all = everyone") },
  async ({ query, limit, scope }) => {
    const r = await (await get(`${BASE}/api/search?q=${encodeURIComponent(query)}&limit=${limit ?? 5}&user=${encodeURIComponent(AUTHOR)}&scope=${scope ?? "app"}`)).json();
    const out = [];
    for (const h of r.records) out.push(`RECORD ${h.id} [${h.status}] "${h.title}" by ${h.author}${h.team ? " (" + h.team + ")" : ""} ${h.createdAt?.slice(0,10)}${h.commitSha ? ` @${h.commitSha.slice(0,7)}` : ""}\n  decision: ${h.decision || "(none)"}`);
    for (const h of r.sessions) out.push(`SESSION ${h.id} ${h.agent} by ${h.author}${h.team ? " (" + h.team + ")" : ""} ${h.endedAt?.slice(0,10)} in ${h.cwd}${h.hadDiff ? "" : " (no code changes — investigation)"}\n  …${(h.snippet||"").replace(/<\/?b>/g,"")}…`);
    return { content: [{ type: "text", text: out.length ? out.join("\n\n") : "No prior art found: nobody on the team has looked at this yet." }] };
  });

server.tool("priorart_get",
  "Fetch a priorart record (findings, evidence, decision) or a session's user prompts by id.",
  { id: z.string().describe("Record or session id from priorart_search") },
  async ({ id }) => {
    let res = await get(`${BASE}/api/records/${id}`);
    if (res.ok) {
      const r = await res.json();
      const txt = [`# ${r.title}  [${r.status}] by ${r.author}`,
        r.commitSha ? `code state: ${r.commitSha}` : "", `question: ${r.question}`,
        "findings:", ...r.findings.map(f => `  - ${f}`),
        "evidence:", ...r.evidence.map(e => `  - ${e.claim} (turn ${e.turn}${e.tool ? ", " + e.tool : ""})`),
        `decision: ${r.decision}`, `outcome: ${r.outcome}`, `tags: ${r.tags.join(", ")}`,
        `session: ${r.sessionId}`].filter(Boolean).join("\n");
      return { content: [{ type: "text", text: txt }] };
    }
    res = await get(`${BASE}/api/sessions/${id}`);
    if (!res.ok) return { content: [{ type: "text", text: `Not found: ${id}` }] };
    const s = await res.json();
    return { content: [{ type: "text", text: `# session ${s.id} (${s.agent}) by ${s.author}${s.team ? " (" + s.team + ")" : ""} ${s.endedAt}\nrepo: ${s.repo ?? "-"}\ncwd: ${s.cwd}\nhadDiff: ${s.hadDiff}\n\nUser prompts:\n${s.prompts}` }] };
  });

await server.connect(new StdioServerTransport());
