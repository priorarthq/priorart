---
name: onboard
description: Set up Prior Art (team memory) step by step. Use when the user runs /priorart:onboard, asks to set up, install, onboard, configure or join Prior Art (or "team memory"), or when Prior Art says it isn't set up yet.
---

# Set up Prior Art, with the user

The user just installed Prior Art and wants it working. They should not need to read any documentation: you do every
step, and ask them only what you can't know. One short question at a time, in plain words, with the answer you'd
suggest. Don't show them commands unless they ask.

All steps use one script, next to this skill's folder:

```
node <this skill's directory>/../priorart/scripts/priorart.mjs <command>
```

Use its absolute path, and run it from the user's current directory (it reads the git repo there). It needs Node 18+.

## 1. Where things stand

Run `status`. It shows what's set up and ends with a **NEXT** list; follow it rather than guessing. If it already says
"Fully onboarded", tell the user they're done and skip to step 6.

## 2. The server

If status says the repo's `.priorart.json` suggests a server, ask just: **"This repo points to the Prior Art server
`<url>`. Is that your team's?"** Use it only if they say yes.

Otherwise ask one question: **"Has your team given you a Prior Art server address? If so, paste it. If you're the
first on your team, say so."**

- **An address** → `config --server <url>`, then `status`. "Server reachable; no name claimed yet" is the normal next
  step. Only "unreachable" is a problem: tell them, and ask them to check the address with whoever runs it.
- **First on the team** (usually the team lead) → step 3.
- **Just for themselves, no team** → Prior Art is team memory; for public answers there's the separate Prior Art
  Commons plugin (github.com/priorarthq/commons-plugin). Tell them that, and stop here unless they want a team server.

Never invent an address.

## 3. Starting a server (only when the team has none)

Explain in one or two sentences: the server is where the team's sessions and findings live; it runs with Docker on
one machine the team can reach (a shared box inside the company network is best; their own laptop is fine to try it).
Ask before running anything. Then:

1. Check Docker is there (`docker info`). If not, tell them to install Docker Desktop and stop here.
2. Get the code and start it, in a folder they agree to (default `~/priorart`):
   `git clone https://github.com/priorarthq/priorart.git ~/priorart && cd ~/priorart && docker compose up -d --build`.
   If port 8080 is taken, use another: `PRIORART_PORT=8090 docker compose up -d --build`.
3. Wait until `http://localhost:<port>/` answers (the first build takes a few minutes), then
   `config --server http://localhost:<port>`.
4. Tell them: teammates need an address *they* can reach, such as this machine's name or IP on the company network
   (`http://<hostname>:<port>`), not `localhost`.

## 4. Who they are

Status suggests a handle from their git email. Ask: **"I'll use `<handle>` as your name in Prior Art. OK?"** Then
`config --handle <handle>`: this claims the name on the server and saves their personal token.

- "Already claimed" and it's **them, on another machine** → they need that machine's Prior Art token: "On your other
  machine, ask your agent for your Prior Art token and paste it here." (It's the `token` value in
  `~/.priorart/config.json` there.) Then `config --token <token>`, which sets their name too.
- Claimed by **someone else** → agree on a different name.

## 5. Their team

This decides who sees their work. Status lists the existing applications and teams, and the repo they're in.

- **The repo already belongs to a team** → "This repo belongs to `<app>/<team>`. Join it?" → `onboard --app <app> --team <team>`.
  If the team has an admin, this sends a join request: tell them who approves it (the output names them), that a
  quick message to that person speeds it up, and that until then their sessions stay private to them. Nothing has
  to be rerun after approval. Carry on with steps 6 and 7 while it's pending.
- **The repo isn't registered** → show the existing teams and ask which one it belongs to; prefer an existing one.
- **No teams exist yet** (they're the first) → suggest short lowercase names, e.g. the product as the application and
  their group as the team: "Create application `billing` with team `payments`?" Only with their OK on the exact
  names: `onboard --app <app> --team <team> --create`. The first person to create an application becomes a server
  admin; they also become the new team's admin.
- `--create` refused → only server admins create applications, and only admins create teams. Tell them who the
  server admins are (the error lists them) and that one of them can create it. Don't work around it.

Onboarding is per repo: in each other repo whose sessions they want shared, `onboard` again (status reminds them).

### Linking their GitLab / GitHub account

Some servers let people see work only from repos they can open on GitLab or GitHub. Status then shows
"repo access: checked with GitHub/GitLab" and, if needed, a NEXT to link an account. Explain in one sentence: "Your
team's Prior Art only shows you work from repos you have access to on `<host>`, so it needs to know your `<host>`
account." Then `git login --host <host>`: show them the link and the code, and when they say they've entered it,
`git login --finish --host <host>`. It only reads who they are; nothing else is shared with Prior Art.

## 6. Capture and checks

- Status shows `plugin` for their agent → the plugin already runs everything in the background; nothing to install.
  In **Codex**, tell them to run `/hooks` once and trust the Prior Art hooks.
- `plugin+duplicate` → hooks from an older install would capture every session twice: offer `uninstall-hooks --agents <agent>`.
- No plugin (installed with `install.sh`) and hooks missing → explain in one sentence (when the agent stops, the
  session is sent to the team server; before each prompt, the team's findings are checked) and, with their OK,
  `install-hooks`.

## 7. Done

Run `status` once more. NEXT says "Fully onboarded", or that the join request is waiting: both are a finished setup.
Then tell them, briefly:

- From now on their agent checks the team's findings before starting work or searching the internet, and their
  sessions in this repo are shared with the team. For another repo, run `/priorart:onboard` there too.
- Try it: "has anyone looked at …?" or "save what we found to Prior Art".
- Only if you just ran `install-hooks` (no plugin): restart the agent once, because hooks load when it starts. With the
  plugin, nothing to restart.

If they created the team, give them a message to paste to teammates, filled in:

> We use Prior Art for shared agent memory. Install the plugin (Claude Code: `/plugin marketplace add priorarthq/priorart`
> then `/plugin install priorart@priorart`; Codex and Copilot CLI: see github.com/priorarthq/priorart), then run
> `/priorart:onboard` (or say "set up Prior Art"). Server: `<url>`. Team: `<app>/<team>`; I'll approve your request.

And offer to add a `.priorart.json` to the repo (`{"serverUrl": "<url>", "application": "<app>", "team": "<team>"}`),
so teammates' setup suggests these by itself. Commit it only if they say so.
