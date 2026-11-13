# Prior Art

**Before your agent starts digging, it checks whether a teammate already dug there.**

Prior Art records your team's coding-agent sessions (Claude Code, Codex, GitHub Copilot CLI) and turns
investigations into records anyone on the team can search: what the problem was, what was tried, what didn't work,
what the answer was, and the commit it was true for. When someone starts a similar task, their agent is shown what a
teammate already found.

You run the server yourself. Sessions go to it and nowhere else.

For a public place where anyone's agent shares fixes, there's a separate plugin:
[Prior Art Commons](https://github.com/priorarthq/commons-plugin). This repo has no public features.

> **Status: early.** It works end to end. Identity is a personal token per person, not SSO yet, and captured
> transcripts are stored as they are, without secret redaction. Run the server inside your network.

## Why

The same investigation gets repeated across a company. Someone finds the root cause of a nightly 502, and a month
later another team digs through the same logs. With agents, the whole trail of an investigation already exists, but
it stays on one laptop. Tools that track agent work tie it to commits, and investigations often end without one.

## How it works

```
 your agent                          your Prior Art server                          a teammate's agent
 ──────────                          ─────────────────────                          ──────────────────
 session starts: note the git state
 session ends: send the transcript ─▶ store the session (who, repo, team, commit)
                                     no code changed? ─▶ draft record ─▶ promoted (optional)
                                     search records and sessions ◀────── before each prompt, before a web
                                     (only repos the teammate can read)  search, or "has anyone looked at…?"
                                                      └───────────────▶ "Alice looked at this on Tuesday: …"
```

**1. Capture.** The plugin adds hooks to your agent. When a session starts, a hook notes the repo's git state (the
commit and which files are changed). When the agent stops, another hook sends the session's transcript to your
server, along with your name, the repo's git remote, the branch, the commit, and whether code changed since the
session started. Before sending, it trims very large tool outputs and drops screenshots; your prompts and the agent's
replies are kept whole. The hook runs after every turn, and the server updates the same session each time.

**2. Which team it belongs to.** The server recognises a repo by its git remote. A session is filed under a team when
you're a member of the team that owns that repo and you ran setup in that repo. Anything else, such as a personal
project, stays private to you.

**3. From session to record.** When a session has been quiet for a few minutes (`PRIORART_EXTRACT_QUIET_SECONDS`,
default 300) and didn't change any code, the server treats it as an investigation and writes a draft record: the
question, the findings, the evidence, the decision, tags, and the commit it was true for. If you've set up a model
endpoint (`PRIORART_LLM_*`), the model writes the draft from the transcript; otherwise it's built from the
conversation's own text. A session that changed code is still stored and searchable, but doesn't get a draft. You
can also save any session on purpose ("save what we found to Prior Art") and promote a draft with a better title
and decision. Promoted records rank higher than drafts.

**4. Search.** Search covers records and the prompts of sessions nobody has written up yet, so a teammate's work can
be found the day it happens. It matches words (Postgres full-text search), and hits are ranked by how many of the
question's words they share. Results only come from repos you're allowed to read (see [Who sees what](#who-sees-what)).

**5. The automatic checks.** These run without anyone asking:
- At the start of each session, the agent gets one rule: check the team's records before searching the internet.
- Before each prompt, the prompt is searched. Only strong matches are shown (they share at least half of the
  prompt's main words, and at least two), and each one only once per session.
- When the agent is about to search or fetch from the web, the query is searched too. If the team already has strong
  matches, that first web call is held back and the agent sees the team's records instead. If they don't answer it,
  the same web call goes through the second time.
- Records and sessions are checked for risky instructions (piping a download into a shell, turning off TLS checks,
  text written to steer an AI agent). These are marked ⚠, and text aimed at agents is never put into the agent's
  context.

**6. Reading more.** `get <id>` shows a record, or a session's prompts. `transcript <session-id>` saves the full
transcript to a file, for when you want every reply and tool call.

## Install

You need Node 18 or newer. Pick your agent below, then do [Set up](#set-up).

### Claude Code

In the Claude Code terminal:

```text
/plugin marketplace add priorarthq/priorart
/plugin install priorart@priorart
```

In the Claude desktop app, `/plugin` isn't available. Run these in a terminal instead:

```bash
claude plugin marketplace add priorarthq/priorart
claude plugin install priorart@priorart --scope user
```

Start a new session afterwards so the plugin loads.

### Codex

```bash
codex plugin marketplace add priorarthq/priorart
codex plugin add priorart@priorart
```

Codex runs a plugin's hooks only after you approve them. Start Codex, run `/hooks`, and trust the Prior Art hooks once.

### GitHub Copilot CLI

```bash
copilot plugin marketplace add priorarthq/priorart
copilot plugin install priorart@priorart
```

The Copilot files follow GitHub's plugin documentation but haven't been tried on a real Copilot CLI install yet. If
something doesn't work, please open an issue.

### Without the plugin

`./install.sh` copies the skills into all three agents (`~/.claude/skills`, `~/.agents/skills` for Codex,
`~/.copilot/skills`). Setup then installs the hooks too. `./install.sh --project /path/to/repo` puts the skills in that
repo's `.github/skills`, so everyone who clones it gets them.

## Set up

In Claude Code run `/priorart:onboard`. In Codex or Copilot CLI, say "set up Prior Art".

Your agent asks a few questions, one at a time, and runs each step:

1. **The server.** Paste the address your team lead gave you. If your team has no server yet, the agent offers to
   start one with Docker on your machine.
2. **Your name.** It suggests one from your git email. This claims the name on the server and saves a personal token
   in `~/.priorart/config.json`.
3. **Your team.** It joins the team the current repo belongs to, or creates the application and team if you're the
   first person on the server. Joining a team that has an admin sends a request the admin approves.

The first person also gets a message to send to teammates (install commands, server address, team name), and the
agent can add a `.priorart.json` to the repo so teammates' setup fills in the server and team.

Setup is per repo. Your sessions in a repo are shared with your team only after you've run setup in that repo.

## Day to day

After setup you don't run anything: capture and the checks in [How it works](#how-it-works) happen on their own
(which checks each agent supports is in [Agents](#agents)). You can also ask directly: "has anyone looked at the checkout 502s?", "save what we found to Prior Art", or "get me the
transcript of that session".

The agent runs one script for all of this:

```bash
priorart.mjs search "checkout returns 502 after 14:00"     # what teammates found
priorart.mjs get <id>                                      # a record or a session's prompts
priorart.mjs transcript <session-id>                       # a session's full JSONL, saved to a file
priorart.mjs save --promote --title "…" --decision "…"     # share what this session found
priorart.mjs status                                        # what's set up, what to do next
```

Agents without skill support can use the MCP server in `mcp/` (`priorart_search`, `priorart_get`).

## Who sees what

- **Identity comes from the token.** Each person claims a name once and gets a personal token. The server never
  trusts a name sent in a request.
- **You see others' work only from repos you can read.** That applies to search, records, sessions and transcripts.
  The server decides this one of two ways:
  - `PRIORART_REPO_ACCESS=team` (default): repos that belong to the teams you're in.
  - `PRIORART_REPO_ACCESS=git`: your GitLab or GitHub permissions. Each person links their account once during setup
    (a link and a code, no token pasted). The server checks each repo with GitLab or GitHub using its own read-only
    token, and remembers the answer for ten minutes.
- **Work is shared only by its author.** A session goes to a team only if its author is in that team and ran setup in
  that repo. Personal projects stay visible to their author alone.
- **Who creates teams.** The first person to create an application on a server becomes a server admin. After that,
  server admins create applications, and team admins or server admins create teams. Joining a team needs an admin's
  approval.
- **Tokens stay with their server.** Your token is sent only to the server in `~/.priorart/config.json`. A repo's
  `.priorart.json` can suggest a server, but it isn't used until you confirm it.

## What gets sent, and where

- **Sent:** the agent's transcript file, your name, the working directory, the git remote, branch and commit, and
  whether code changed during the session. Very large tool outputs and screenshots are trimmed first; prompts and
  replies are kept.
- **To:** only your server. Nothing goes to us or anyone else.
- **Stored:** in your Postgres, whole, so records can be extracted again later with a better model.
- **Extraction:** if you set `PRIORART_LLM_*`, transcripts are sent to that model endpoint to write the drafts. Use
  your company's approved gateway. Without it, drafts are built from the conversation's own text.
- **No secret redaction yet.** If your sessions can contain credentials, keep that in mind before sharing a server.
- **Turning it off:** uninstall the plugin, or run `priorart.mjs uninstall-hooks` if you used `install.sh`.

## Running the server

From the published image, no clone needed:

```bash
curl -fsSLO https://raw.githubusercontent.com/priorarthq/priorart/main/deploy/docker-compose.yml
docker compose up -d                          # http://localhost:8080; PRIORART_PORT=8090 if 8080 is taken
```

Or from source:

```bash
git clone https://github.com/priorarthq/priorart.git && cd priorart
docker compose up -d --build                  # API on http://localhost:8080, Postgres on :5434
```

| Setting (env) | Default | |
|---|---|---|
| `PRIORART_DB_URL`, `_USER`, `_PASSWORD` | `jdbc:postgresql://localhost:5434/priorart` | Postgres |
| `PRIORART_LLM_PROVIDER` | `none` | `openai` for any OpenAI-compatible chat endpoint |
| `PRIORART_LLM_BASE_URL`, `_API_KEY`, `_MODEL` | `https://api.openai.com/v1`, –, `gpt-4o-mini` | extraction model |
| `PRIORART_EXTRACT_QUIET_SECONDS` | `300` | how long a session must be quiet before its draft is made |
| `PRIORART_TEAM_AUTH` | `required` | `off` for a single-user local setup without tokens |
| `PRIORART_SERVER_ADMINS` | – | names that may create applications |
| `PRIORART_REPO_ACCESS` | `team` | `git` to follow GitLab/GitHub permissions |
| `PRIORART_GIT_GITLAB_URL`, `_CLIENT_ID`, `_TOKEN` | `https://gitlab.com`, –, – | GitLab: an OAuth application with device flow and scope `read_user`, and a server token with `read_api` |
| `PRIORART_GIT_GITHUB_CLIENT_ID`, `_TOKEN` | –, – | GitHub: an OAuth app with device flow, and a server token that can read the org's repos and collaborators. `_HOST`, `_API`, `_WEB` for GitHub Enterprise |
| `PRIORART_PORT` (compose) | `8080` | host port |

On your machine, `~/.priorart/config.json` holds the server address, your name and your token. A repo's
`.priorart.json` can suggest `serverUrl`, `application` and `team` for setup.

## Agents

| | Claude Code | Codex | GitHub Copilot CLI |
|---|---|---|---|
| Install | plugin | plugin (trust hooks with `/hooks`) | plugin |
| Capture | `SessionStart`, `Stop` | `SessionStart`, `Stop` | `sessionStart`, `agentStop` |
| Rule at session start | yes | yes | yes |
| Records shown before each prompt | yes | yes | no (Copilot doesn't allow it) |
| First web call held back | yes | no (web search runs on OpenAI's side) | yes |
| Tested | daily use | with Codex-format sessions | not yet on a real install |

Other agents (Cursor and so on) need a transcript reader on the server, one class per agent. Contributions welcome.

## Repository layout

```
plugins/priorart/  the plugin: skills (setup and the main skill, zero-dependency Node scripts) and each agent's hooks
server/            Spring Boot 3.5, Java 21, Postgres 16: capture, extraction, search, teams and access
deploy/            docker-compose.yml that runs the published image
mcp/               optional MCP server
tests/skill/       tests for the plugin scripts
docs/              database schema diagram
PLAN.md            plan and decisions (council/ is the review that shaped them)
```

## Development

```bash
docker compose up -d postgres                 # database only
(cd server && mvn spring-boot:run)            # API on :8080
(cd server && mvn verify)                     # unit and integration tests (needs Docker)
node --test 'tests/skill/*.test.mjs'          # plugin script tests
claude plugin validate plugins/priorart       # plugin manifest check
```

CI runs the server tests, the plugin tests and checks, the MCP server check, and the Docker build on every push and
pull request.

## Not done yet

- **SSO.** Identity is a personal token per person. Company SSO would replace claiming a name.
- **Secret redaction** of captured transcripts.
- **Search by meaning.** Search matches words, so "times out" doesn't find "timeout". The agent is told to search
  again with the words a record would use.
- **Copilot CLI** on a real install.

## License

Apache-2.0. See [LICENSE](LICENSE).
