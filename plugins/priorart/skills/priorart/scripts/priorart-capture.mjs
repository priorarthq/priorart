#!/usr/bin/env node
// Prior Art capture hook. Two modes, both silent: never block the agent, never print, log to ~/.priorart/capture.log.
//
//   priorart-capture.mjs <agent> --start   session start: snapshot the git state of the working directory
//   priorart-capture.mjs <agent>           agent stop (every turn): send the transcript + whether code changed
//
// Payloads:
//   Copilot CLI  sessionStart / agentStop -> { sessionId, cwd, transcriptPath, ... }   (camelCase)
//   Claude Code  SessionStart / Stop      -> { session_id, cwd, transcript_path, ... } (snake_case)
// Config: ~/.priorart/config.json { "serverUrl": "http://priorart.corp:8080", "author": "alice" }  or env PRIORART_URL / PRIORART_AUTHOR.

import { readFileSync, writeFileSync, existsSync, appendFileSync, mkdirSync, statSync, readdirSync, rmSync } from "node:fs";
import { execSync } from "node:child_process";
import { createHash } from "node:crypto";
import { homedir } from "node:os";
import { join, dirname } from "node:path";
import { compact } from "./priorart-compact.mjs";

const home = join(homedir(), ".priorart");
const stateDir = join(home, "state");
try { mkdirSync(stateDir, { recursive: true }); } catch {}
const log = (m) => { try { appendFileSync(join(home, "capture.log"), `${new Date().toISOString()} ${m}\n`); } catch {} };

let cfg = {};
try { cfg = JSON.parse(readFileSync(join(home, "config.json"), "utf8")); } catch {}
// Server: env, then ~/.priorart/config.json, and nothing else. Never a repo's .priorart.json (a cloned repo must not be
// able to send your sessions somewhere) and never a default: with no server configured, nothing is sent.
const serverUrl = process.env.PRIORART_URL || cfg.serverUrl || null;
const author = process.env.PRIORART_AUTHOR || cfg.author || process.env.USER || "unknown";
const token = process.env.PRIORART_TOKEN || cfg.token || null;

let payload = {};
try { payload = JSON.parse(readFileSync(0, "utf8") || "{}"); } catch (e) { log(`bad stdin: ${e}`); }

const args = process.argv.slice(2);
const isStart = args.includes("--start");
const agent = args.find((a) => !a.startsWith("--")) || (payload.session_id ? "claude-code" : "copilot-cli");
const sessionId = payload.sessionId || payload.session_id;
const cwd = payload.cwd || process.cwd();
const transcriptPath = payload.transcriptPath || payload.transcript_path;

const git = (a) => { try { return execSync(`git ${a}`, { cwd, stdio: ["ignore", "pipe", "ignore"], maxBuffer: 64 * 1024 * 1024 }).toString().trim(); } catch { return null; } };

/**
 * Fingerprint of the working tree: HEAD plus a hash of all uncommitted changes (tracked diff + untracked file list).
 * Comparing start vs. stop tells whether *this session* changed code — a tree that was already dirty, or a session
 * whose changes were committed, no longer fool the no-diff signal.
 */
function snapshot() {
  const head = git("rev-parse HEAD");
  if (head === null) return null; // not a git repo
  const changes = (git("diff HEAD --no-ext-diff --binary") ?? "") + "\n--untracked--\n" + (git("ls-files --others --exclude-standard") ?? "");
  return { head, changes: createHash("sha256").update(changes).digest("hex") };
}

if (!sessionId) { log(`skip agent=${agent}: no session id in payload`); process.exit(0); }
const stateFile = join(stateDir, `${agent}-${String(sessionId).replace(/[^A-Za-z0-9_.-]/g, "_")}.json`);

if (isStart) {
  const snap = snapshot();
  if (snap && !existsSync(stateFile)) writeFileSync(stateFile, JSON.stringify({ ...snap, cwd, at: new Date().toISOString() }));
  // Housekeeping: forget snapshots older than 7 days.
  try {
    const cutoff = Date.now() - 7 * 24 * 3600 * 1000;
    for (const f of readdirSync(stateDir)) { const p = join(stateDir, f); if (statSync(p).mtimeMs < cutoff) rmSync(p); }
  } catch {}
  log(`start agent=${agent} session=${sessionId} snapshot=${snap ? snap.head.slice(0, 8) : "none (not git)"}`);
  process.exit(0);
}

if (!serverUrl) { log(`skip agent=${agent} session=${sessionId}: no server configured (priorart.mjs status)`); process.exit(0); }
if (!transcriptPath || !existsSync(transcriptPath)) {
  log(`skip agent=${agent} session=${sessionId} transcript=${transcriptPath} (missing)`);
  process.exit(0);
}

const repo = git("remote get-url origin");
const gitBranch = git("rev-parse --abbrev-ref HEAD");
const now = snapshot();
let start = null;
try { start = JSON.parse(readFileSync(stateFile, "utf8")); } catch {}
let hadDiff, diffBasis;
if (!now) { hadDiff = false; diffBasis = "not-git"; }
else if (start) { hadDiff = start.head !== now.head || start.changes !== now.changes; diffBasis = "vs-start"; }
else { hadDiff = (git("status --porcelain") ?? "").length > 0; diffBasis = "dirty-tree"; } // hooks installed mid-session

const raw = readFileSync(transcriptPath, "utf8");
const { text: transcript, note } = compact(raw, { agent });
const startedAt = start?.at ?? (() => { try { return statSync(transcriptPath).birthtime.toISOString(); } catch { return null; } })();

const body = { agent, sessionId, author, cwd, repo, gitBranch, headSha: now?.head ?? null, hadDiff, startedAt,
               endedAt: new Date().toISOString(), transcriptPath, transcript };

try {
  const res = await fetch(`${serverUrl}/api/sessions`, {
    method: "POST", headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  });
  log(`sent agent=${agent} session=${sessionId} hadDiff=${hadDiff} (${diffBasis}) bytes=${transcript.length}${note} -> ${res.status} ${(await res.text()).slice(0, 300)}`);
} catch (e) {
  log(`send failed agent=${agent} session=${sessionId}: ${e}`);
}
process.exit(0);
