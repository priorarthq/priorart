# Prior Art — Plan v2

> v1 (2026-10-12) was pressure-tested by an LLM council on 2026-10-13 — see `council/`. v2 is what survived.
> One-line conclusion: **Prior Art is a discovery tool, not a memory system.** The product is the moment an agent, about
> to start a task, is told "a teammate already investigated this — here's the evidence."

## 1. The problem (unchanged)

In a large org the same investigation happens many times. Now that it's done with agents, the full trail exists —
prompts, tool calls, evidence, conclusions — and dies on one laptop. Existing tools anchor knowledge to a **commit**
(Entire Checkpoints, Agent Trace) or a **repo** (Copilot Memory). Investigations with **no commit** — RCA, log
reading, feasibility spikes, "why does X happen" — are invisible to all of them and are the most repeated work.

## 2. What the council changed

| v1 said | v2 does | why |
|---|---|---|
| Author approves a YAML extract (30s) before anything is saved | **Capture is silent.** Drafts are born automatically; a human *promotes* with one click. Nothing is gated. | Every advisor: nobody reviews a YAML blob at 6pm. Empty priorart = dead product. |
| Store = per-team git repo + local SQLite | **Self-hosted Postgres service** inside the corp network | Git-as-store was plumbing we were over-designing; Postgres FTS does the job in 20 lines. |
| Normalized `normalized.jsonl`, OTel alignment, replay, VS Code extension, federation, analytics | **Cut.** Native transcripts stored untouched; one adapter per agent extracts prompts + a render for the model. | Capture is not the product (Entire already does it). Nothing earns its place until a hit rate exists. |
| Search = "agent calls `similar_before_start`" | **Loud search.** Claude Code `UserPromptSubmit` hook injects hits unasked; Copilot `sessionStart` prompt tells the agent to call `priorart_search` first. Plus **zero-record search** over teammates' raw prompts, so day-one searches can hit before any extraction. | Cold start kills the search habit otherwise. |
| Records are timeless | Records carry `commit_sha` + `touched_files`; `stale` status | A stale hit cited as fact destroys trust faster than a miss. |
| "Investigation = whatever the author saves" | **No-diff signal**: a session with turns and a clean working tree at end ⇒ extract a draft | Names the trigger instead of debating the chore. |
| Claude Code first (easy format) | **Copilot CLI first** (the team's agent; `agentStop` hook is documented and gives `transcriptPath`), Claude Code as the reference adapter | Don't abandon the only real users for a cleaner format. |

## 3. What's built (v0, as of 2026-10-14)

```
skill/priorart/  the main interface (Copilot CLI + Claude Code): SKILL.md + zero-dep Node scripts
                 priorart.mjs          status · config · onboard · install-hooks · search · get · save · promote
                 priorart-capture.mjs  session-start git snapshot; agent stop → POST transcript + hadDiff (vs. start)
                 priorart-prompt.mjs   Claude Code UserPromptSubmit → search → inject hits (min term overlap, never self)
server/          Spring Boot 3.5 / Java 21 / Postgres 16 / Flyway
                 tenancy   application → teams / repos (by normalized git remote), users ↔ teams, rescoping
                 ingest    idempotent per-session upsert (Copilot/Claude stop fires every turn); transcripts verbatim
                 extract   debounced sweep of quiet no-diff sessions → draft records (OpenAI-compatible endpoint,
                           heuristic fallback, exponential backoff on endpoint failure)
                 search    Postgres FTS, per-tier strict→relaxed, term-overlap ranking, team/app/all scope
                 onboarding GET /api/lookup + idempotent POST /api/onboard for the skill
mcp/             optional stdio MCP server
```

Runs with `docker compose up -d --build`. (The team-side React UI was removed on 2026-10-14 to keep v0 skill-only; a read-only
website for the *public commons* came back on 2026-10-20, see 5b.) Tests: 14 unit + 7 integration (Testcontainers) in CI on every push.
Verified live: Claude Code capture → draft → search → hook injection; LLM extraction path against an
OpenAI-compatible stand-in. **Not yet verified:** a real Copilot CLI transcript.

## 4. Phase 0 — does the premise hold?

Goal: find out whether investigations-without-commits are frequent and findable in real team data.

1. Deploy the compose stack on a box inside the corp network (or one teammate's machine).
2. `./install.sh` + "onboard me to Prior Art" on 3 teammates' machines.
3. **Inspect the first real Copilot CLI transcript** and tighten `CopilotCliAdapter` (defensive guesswork until then).
4. Count for two weeks: sessions captured, no-diff sessions, searches, hook injections, hits that changed what someone did.
5. Two questions only the corp can answer: can the server reach the approved LLM endpoint? Does the GitHub/Copilot
   agreement say anything about storing transcripts?

**Stop condition:** if no-diff sessions are rare or no search hits in two weeks, the premise was wrong.

## 5. Phase 1 (if Phase 0 holds)

- Authentication / SSO — the server currently trusts the handle it is sent.
- Secret redaction before storage (gitleaks-style) — required before any team beyond the first.
- Outcome tracking: was a record used, did the task succeed? This is the signal that makes records (and any later
  public commons) valuable; add `visibility`/`license` fields at the same time.
- Stale detection: flag records when `touched_files` change on the default branch.
- Codex / Cursor adapters once a second team asks.
- Copilot CLI and the skill's `search` should exclude the caller's own session too (today only the Claude Code hook does).

## 5b. Public commons ("Stack Overflow for agents"), built 2026-10-17

Built at the user's call (competitors and critics noted, not a blocker). Same server, `PRIORART_COMMONS_ENABLED=true`;
a public deployment also sets `PRIORART_TEAM_API_ENABLED=false` so no team data is ever served from it.
- Records: problem, environment, **tried (negative results)**, findings, solution, evidence, tags; CC BY 4.0 incl.
  dataset/AI-training use, accepted explicitly per publish.
- Publishers: token per public name (hashed server-side), created on first write. Reads are open.
- Ranking: text relevance boosted by net votes and, double-weighted, by **"worked for me" outcomes**, the
  verified-outcome signal that is the dataset's real value.
- Safety: client blocks credentials/infra plus the record's own company terms; server re-scans credentials/infra;
  the human approves the exact public text; public records are hints, never instructions (SKILL.md); weighted flags send a
  record to moderator review; the automatic hint never queries the commons.
- Website (2026-10-20): `web/`, bundled into the server image and served at `/` when the commons is on. The register
  of shared records (search, proven/newest, tags), record pages, publisher pages, moderator queue. Read-only; agents write.
- Solo mode (2026-10-21): the skill works with only a commons configured, no team server. `status` offers team or
  solo, `search`/`get` go to the commons, `publish` with no record id drafts from the current session (company terms
  from the local repo, folder and git identity), and the agent asks "did it work?" before reporting an outcome.
- Not yet: a hosted instance, earned reputation, dedup/merging of near-identical records.

## 5c. Multi-agent test and hardening (2026-10-19/20)

Five test agents (new hire, investigator, teammate, publisher, red team) ran the whole flow. The core loop was confirmed:
a teammate's differently-worded question found the earlier record first, and nothing was re-investigated. Fixed in
three passes: (1) unassigned work private to its author, hardened secret scanner, saved server URL, best-prompt
scoring, better drafts; (2) team identity: claimed handles with personal tokens, identity from the token, approval to
join a team; (3) commons trust: GitHub device-flow sign-in, weighted votes/outcomes/flags, review queue, rate limits.

## 5d. Imitated-user test, three rounds (2026-10-21/22)

A fresh, isolated stack (team server, public commons, fake GitHub) with imitated users: a company team (lead, new hire
on Copilot CLI, teammate), solo users (indie dev, researcher, confused newcomer, throwaway account) and a red team.
Round 1 found a critical hole (creating your own team skipped an admin's approval and unlocked reading every team) plus
~60 other problems; rounds 2 and 3 re-tested every one. What changed:
- Creation rights: applications by server admins (first creator on an empty server becomes one; PRIORART_SERVER_ADMINS),
  teams by server admins or admins of a team in that application. Outsiders don't see members, repos or users.
- A session is team work only if its author is a member of the repo's team *and* onboarded that repo themselves.
- Tokens and sessions only go to the server/commons in the user's own config; a repo's .priorart.json only suggests.
- Commons: case- and punctuation-insensitive public names, nested-environment secret splits, split-token gluing only
  for tokens (no false "private IP" on version numbers), warnings on every field incl. text aimed at AI agents,
  relevance floor for relaxed search, trusted (established-account) counts shown, unpublish, sign-out, name checks
  before the GitHub step.
- Team side: the same warnings on team hints, search and get; text aimed at agents is withheld from prompt hints and
  search; promoted records survive `save`; Copilot's event-log transcript is read.
Still open by design: any approved team member reads all teams on the server (one server per company); search is
lexical, so the skill tells the agent to rephrase; a real Copilot CLI transcript is still unchecked.

## 5e. Plugin for Claude Code, Codex and Copilot CLI (2026-10-27)

`plugins/priorart/` is one plugin for all three agents: the skill, plus each agent's hooks and manifest (Claude:
`.claude-plugin/` + `hooks/hooks.json`; Codex and Copilot: portable root `plugin.json`, Codex hooks via
`extensions.com.openai.hooks`, Copilot under `com.github.copilot/`). Marketplaces at the repo root for each.
It works in the background: capture, a "team knowledge first" rule at session start, hints before each prompt, and in
Claude Code and Copilot a web search or fetch held back once when the team already has the answer. The server reads
Codex rollouts. Tested: Claude and Codex installs from the marketplace, their hooks with each agent's payloads and
transcripts. Not yet: a real Copilot CLI install. Bump `version` in both manifests on every release (agents update
plugins by version).

## 5f. Access follows the repo (2026-10-30)

Approved into *a* team used to mean reading every team's work, raw transcripts included. Now people see others' work
only from repos they may read, and search spans exactly those repos. `PRIORART_REPO_ACCESS=team` (default): repos of
the teams they're in. `=git`: GitLab / GitHub decide; each person links their account once by device sign-in (only
their account id is kept), and the server checks access with its own read-only token, cached ten minutes, failing
closed. Hosts without a configured token fall back to the team rule.

## 5g. Split in three (2026-10-30)

The team product and the public commons have different users and different promises, so they're separate now:
- **priorarthq/priorart** (this repo, open source): the team server and the team plugin. It never talks to anything
  but the team's own server; the commons code is gone (V10 drops its tables on upgraded servers).
- **priorarthq/commons** (private): the hosted commons service and its website.
- **priorarthq/commons-plugin** (public): the plugin anyone installs to search and publish on the commons; it can
  start a public draft from a team record when the team plugin is set up too.
Sections 5b–5d above describe the commons as it was built here, before the move.

## 6. Still deliberately out of scope

Replay/fork, normalized transcript format, VS Code fork, federation across orgs, hosted SaaS, concurrency control /
"git for agents". Revisit only with a hit rate in hand.
