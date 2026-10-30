# Prior Art

**Before your agent starts digging, it finds out whether a teammate already dug there.**

Prior Art captures your team's AI-agent sessions (Claude Code, Codex, GitHub Copilot CLI) and turns the investigations
in them (root-cause hunts, log analysis, "why does X happen" spikes) into searchable records. When anyone on the team
starts a similar task, their agent is told what a teammate already found: what was tried, what failed, what the
answer was, and which commit it was true for.

It runs on your own infrastructure. Transcripts go to your Prior Art server and nowhere else.

It never talks to anything but your own server. Looking for a *public* place where anyone's agent shares fixes, a
"Stack Overflow for agents"? That's a separate plugin:
[Prior Art Commons](https://github.com/priorarthq/commons-plugin).

> **Status: early (v0).** It works end to end. Identity is a personal token per handle (claimed once), not yet SSO,
> and secret redaction of *captured transcripts* isn't there yet. Run it inside your network. See
> [What's not done yet](#whats-not-done-yet).

## Why

- The same investigation gets repeated across a large org. Someone finds the root cause, writes "I checked X, Y, Z" in
  a ticket, and a month later another team does it all again.
- With agents, the whole trail already exists (prompts, tool calls, evidence, conclusions), but it dies on one laptop.
- Tools that track agent work anchor it to **commits**. Investigations often produce no commit, so they're the work
  that gets lost, and the work most often repeated.

## How it works

```
 your agent ──hooks──▶ capture ──▶ "no code changed?" ──▶ draft record ──▶ promote (1 click, optional)
                                       (investigation)     question · findings · evidence · decision · commit
                                                                   │
 a teammate's agent ◀── "Alice looked at this on Tuesday: …" ◀── search (before work starts)
```

1. **Capture is silent.** Agent hooks send each session's transcript to your server when the agent stops.
2. **Investigations are detected automatically.** A session that ends without changing code, compared against the git
   state when it started, becomes a **draft record**, extracted by an LLM you configure (or a simple fallback).
3. **Search is loud.** Before work starts, the agent searches Prior Art. In Claude Code a hook does this on every
   prompt and stays quiet unless a hit really overlaps. Teammates' raw sessions are searchable too, before anyone
   writes anything up.
4. **People only confirm.** Nobody has to write documentation. Promoting a draft is optional and makes it rank first.
   Records keep the commit they were true for, so stale ones can be spotted.

Everything is scoped by **application → team → repo**, and repos are recognised by their git remote. Teams see their
own application's work by default. Work that hasn't been assigned to a team yet is visible to everyone.

## Get started

**1. Install the plugin** in your agent.

Claude Code:

```text
/plugin marketplace add priorarthq/priorart
/plugin install priorart@priorart
```

Codex:

```bash
codex plugin marketplace add priorarthq/priorart
codex plugin add priorart@priorart
```

GitHub Copilot CLI:

```bash
copilot plugin marketplace add priorarthq/priorart
copilot plugin install priorart@priorart
```

**2. Run `/priorart:onboard`** in Claude Code, or say *"set up Prior Art"* in Codex or Copilot CLI.

Your agent takes it from there. It asks a few questions, one at a time, and does each step itself: your team's
server (it starts one for you if your team doesn't have one yet), your name, and your team. If you're the first on
the team, it ends with a message to paste to your teammates, and it can add a `.priorart.json` to your repo so their
setup fills itself in. Restart the agent once at the end.

That's all. From then on it works in the background:

- Every session starts with one rule: check the team's knowledge before searching the internet.
- Before each prompt, your agent is shown what teammates already found on the same problem.
- In Claude Code and Copilot CLI, a web search the team already has an answer to is held back once, and your agent
  reads the team's record first.
- When a session ends without code changes, it becomes a draft record the whole team can search.

Just ask things like *"has anyone looked at the checkout 502s?"* or *"save what we found to Prior Art"*.

<details>
<summary>Without the plugin, or running the server by hand</summary>

`./install.sh` copies the skills into Claude Code, Codex and Copilot CLI instead of the plugin; setup then also
installs the hooks. `./install.sh --project /path/to/repo` puts them in a repo's `.github/skills`, so everyone who
clones it gets them.

The server is a Docker Compose stack (API on <http://localhost:8080>, Postgres on :5434):

```bash
git clone https://github.com/priorarthq/priorart.git && cd priorart
docker compose up -d --build                  # PRIORART_PORT=8090 if 8080 is taken
```

</details>

## Day to day

The skill handles this in conversation. Under the hood it runs one zero-dependency script:

```bash
priorart.mjs search "checkout returns 502 after 14:00"     # before starting work
priorart.mjs get <id>                                      # read a record or a teammate's session
priorart.mjs save --promote --title "…" --decision "…"     # share what this session found
priorart.mjs status                                        # what's configured, what to do next
```

Agents without skills can use the optional MCP server in `mcp/` (`priorart_search`, `priorart_get`).

## Identity and visibility

- **Team server.** Each person claims their handle once, which `config --handle` does during onboarding. They get a
  personal token, and every hook, skill and MCP call sends it. The server takes who you are **from the token**, never
  from the request. Joining a team that has an admin creates a request the admin approves (`requests` / `approve` /
  `deny`). Creating a team makes you its admin.
- **Who creates what.** Whoever creates the first application on a new server becomes a *server admin*. After that,
  applications are created by server admins (`server-admin <handle>` adds one), and teams by server admins or admins
  of a team in that application. Otherwise anyone could create their own team to skip an admin's approval.
- **Who sees what: only repos you can read.** Search spans every repo you may read and leaves out the rest; the same
  rule covers sessions, raw transcripts (JSONL) and records. Which repos you may read is decided one of two ways:
  - `PRIORART_REPO_ACCESS=team` (default): repos of the teams you're in.
  - `PRIORART_REPO_ACCESS=git`: your GitLab or GitHub permissions. Each person links their account once
    (`/priorart:onboard` asks; GitLab/GitHub device sign-in, no token pasted anywhere), and the server asks the git
    host, with its own read-only token, whether that account can read the repo. Lose access on GitLab, lose it here
    within ten minutes. Repos on hosts the server isn't set up for use the team rule.

  Work is shared at all only when its author is in the team that owns the repo and onboarded that repo themselves
  (a teammate linking your repo never shares your sessions). Personal side projects, and sessions by someone not
  approved yet, are visible **only to their author**.
- **Tokens stay with their server.** Your personal token is sent only to the server in `~/.priorart/config.json`,
  and the hooks send sessions nowhere else. A cloned repo's `.priorart.json` can suggest a server but can't redirect
  your token or your sessions.

## What gets captured, and where it goes

- **Sent:** the agent's transcript file (verbatim), your handle, the working directory, the git remote, branch and
  commit, and whether code changed during the session.
- **To:** only the server URL in `~/.priorart/config.json`. Nothing goes to us or anyone else.
- **Stored:** in your Postgres. Transcripts are kept whole so records can be re-extracted later with a better model.
- **Extraction:** with `PRIORART_LLM_*` set, transcripts are sent to *that* endpoint. Use your company's approved
  gateway. Without it, drafts are built locally from the first prompt.
- **Turning it off:** `priorart.mjs uninstall-hooks` removes the hooks and leaves your other hooks untouched.

There is **no secret redaction yet**. If your sessions can contain credentials, don't point the hooks at a shared
server until that lands.

## Configuration

| Server (env) | Default | |
|---|---|---|
| `PRIORART_DB_URL`, `_USER`, `_PASSWORD` | `jdbc:postgresql://localhost:5434/priorart` | Postgres |
| `PRIORART_LLM_PROVIDER` | `none` | `openai` = any OpenAI-compatible chat endpoint |
| `PRIORART_LLM_BASE_URL`, `_API_KEY`, `_MODEL` | `https://api.openai.com/v1`, –, `gpt-4o-mini` | extraction model |
| `PRIORART_EXTRACT_QUIET_SECONDS` | `300` | extract a session once it's been quiet this long |
| `PRIORART_TEAM_AUTH` | `required` | `off` = open single-user mode (identity taken from requests) |
| `PRIORART_REPO_ACCESS` | `team` | `git` = repo access follows GitLab/GitHub permissions (set a host below) |
| `PRIORART_GIT_GITLAB_URL`, `_CLIENT_ID`, `_TOKEN` | `https://gitlab.com`, –, – | GitLab: an OAuth application (device flow, scope `read_user`) for people to link their account, and a server token with `read_api` that can see the group's projects and members |
| `PRIORART_GIT_GITHUB_CLIENT_ID`, `_TOKEN` (+ `_HOST`, `_API`, `_WEB` for GitHub Enterprise) | –, – | GitHub: an OAuth app with device flow enabled, and a server token that can read the org's repos and collaborators |
| `PRIORART_SERVER_ADMINS` | – | handles that may create applications (whoever creates the first one also becomes one) |
| `PRIORART_PORT` (compose) | `8080` | host port, if 8080 is taken on your machine |

| Client | |
|---|---|
| `~/.priorart/config.json` | `serverUrl`, `author` (your handle), `scope` (`team` / `app` / `all`). Written by `priorart.mjs config` |
| `.priorart.json` in a repo | `serverUrl`, `application`, `team` hints for onboarding |
| `PRIORART_URL`, `PRIORART_AUTHOR`, `PRIORART_SCOPE` | env overrides |

## Supported agents

| Agent | Capture | Team first, before work and before the web | Status |
|---|---|---|---|
| Claude Code | `SessionStart` + `Stop` hooks | session rule, hint before each prompt, web search/fetch held back once when the team has it | used daily; plugin tested |
| Codex | `SessionStart` + `Stop` hooks | session rule, hint before each prompt (its web search runs on OpenAI's side, out of a hook's reach) | plugin and hooks tested with Codex-format sessions |
| GitHub Copilot CLI | `sessionStart` + `agentStop` hooks | session rule, web fetch/search held back once when the team has it | follows GitHub's documented formats, not yet tried on a real Copilot install; please report issues |
| Others (Cursor, …) | adapter needed (one class per agent) | MCP server | contributions welcome |

## Repository layout

```
plugins/priorart/  the plugin for Claude Code, Codex and Copilot CLI: the skill (SKILL.md + zero-dep scripts: CLI,
                 capture, prompt hint, team-first gate) and each agent's hooks and manifest
server/          Spring Boot 3.5 / Java 21 / Postgres 16: ingest, extraction, search, onboarding API
mcp/             optional stdio MCP server
docs/            schema diagram (docs/schema.html)
PLAN.md          plan and decisions; council/ is the review that shaped them
```

## Development

```bash
docker compose up -d postgres                 # database only
(cd server && mvn spring-boot:run)            # API on :8080
(cd server && mvn verify)                     # unit + integration tests (integration tests need Docker)
```

CI runs the server tests, the skill tests and checks, the MCP server check, and the Docker build on every push and PR.

## What's not done yet

- **SSO.** Identity is a personal token per claimed handle. Corporate SSO/OIDC would replace the claim step.
- **Secret redaction** before storage.
- **Semantic search.** Matching is full-text, so synonyms ("504" vs "timeout") don't match yet.
- **Copilot CLI transcript verification** (see above).

## License

Apache-2.0. See [LICENSE](LICENSE).
