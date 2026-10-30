#!/usr/bin/env bash
# Installs the Prior Art skill for Copilot CLI, Claude Code and Codex without the plugin (the plugin is the easier way:
# see README). Everything else (server, handle, team, hooks) is done by the agent: open it in a repo and say
# "onboard me to Prior Art".
#
#   ./install.sh                 personal install: ~/.copilot/skills, ~/.claude/skills and ~/.agents/skills (Codex)
#   ./install.sh --project DIR   commit-able install into DIR/.github/skills/priorart (every clone gets the skill)
set -euo pipefail
SKILLS="$(cd "$(dirname "$0")" && pwd)/plugins/priorart/skills"
command -v node >/dev/null || { echo "Prior Art needs Node 18+ on PATH"; exit 1; }

# Each agent gets two skills side by side: priorart (the tool) and priorart-onboard (guided setup; "onboard" inside the
# plugin, renamed here so it can't clash with other skills).
copy() {
  rm -rf "$1/priorart" "$1/priorart-onboard"; mkdir -p "$1"
  cp -R "$SKILLS/priorart" "$1/priorart"; chmod +x "$1"/priorart/scripts/*.mjs
  cp -R "$SKILLS/onboard" "$1/priorart-onboard"
  sed -i.bak 's/^name: onboard$/name: priorart-onboard/' "$1/priorart-onboard/SKILL.md" && rm "$1/priorart-onboard/SKILL.md.bak"
  echo "installed $1/priorart and $1/priorart-onboard"
}

if [[ "${1:-}" == "--project" ]]; then
  DIR="${2:?usage: ./install.sh --project <repo dir>}"
  copy "$DIR/.github/skills"
  echo "Commit .github/skills/priorart and .github/skills/priorart-onboard (and optionally a .priorart.json with {\"serverUrl\",\"application\",\"team\"}) so teammates get it on clone."
else
  copy "$HOME/.copilot/skills"
  copy "$HOME/.claude/skills"
  copy "$HOME/.agents/skills"
fi
echo
echo "Next: open Claude Code, Codex or Copilot CLI in a repo and say: set up Prior Art"
