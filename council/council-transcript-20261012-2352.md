# Council Transcript — Hive Mind — 20261012-2352

## Original question
"we did not grill it yet" — pressure-test PLAN.md before Phase 0.

## Framed question
Pressure-test the Hive Mind plan (PLAN.md v1). First users: founder + team inside a corp, data cannot leave the network, team uses GitHub Copilot CLI, product must stay agent-agnostic. Core bet: investigations with no commit are invisible to Entire / Copilot Memory / Agent Trace. Hive = trajectories + author-approved investigation records, per-team git repo, SQLite/FTS, MCP server. Questions: niche real?; approval gate kills adoption?; per-team git right store?; Copilot-CLI-first feasible?; sellable, to whom?; biggest failure reason; what to cut/change.

## Advisor responses

### The Contrarian

The fatal flaw is in section 3.2, and the plan already admits it in Open Question 5: the record only exists if a human approves it, and nobody will. Every knowledge-capture tool in history (wikis, Confluence, ADRs, post-mortem templates) died on the same hill — the person with the knowledge gains nothing from writing it down; the beneficiary is a stranger a month later. "30 seconds" is a fantasy: reviewing an LLM extract of a 2-hour log-reading session for correctness and redaction is five minutes of unpaid work at the exact moment the dev wants to close the terminal. Approval rate will be under 10%, the hive will be nearly empty, `hive.similar_before_start` will return nothing, agents will learn to ignore it, and the loop never closes. Phase 1's success metric ("one hit") is achievable by the founder alone and proves nothing about the team.

(1) The niche is real but thin and undefended. GitHub owns Copilot's session data, the hooks, and the corp's Copilot contract. Copilot Memory going org-scoped with longer retention is a config change for them. The "they index commits" defense evaporates the day they index sessions, which is the obvious next step.

(3) Per-team git repo: trajectories are MBs of tool output, committed by many people, on LFS, inside a corp with repo-size policies and security review. You're asking a security team to approve a repo whose purpose is "store everything agents saw," redacted by regex. That's a 6-month ticket, not Phase 1.

(4) Copilot CLI first is backwards. ~/.copilot/ internals are undocumented and will change; Phase 0 becomes reverse-engineering a moving target instead of testing the thesis.

Cut: approval gate, replay/fork, VS Code extension, federation, open-core story. Change: capture is silent and automatic; records are unapproved drafts, auto-redacted, surfaced with a "draft" label; the dev's only action is thumbs-up when a hit is useful. Prove retrieval value first on Claude Code (documented format), then earn the Copilot pain. If three months in nobody has said "oh, Alice already did this," it's a weekend tool.

### The First Principles Thinker

You're asking the wrong question. Every one of the six questions assumes the product is a memory system. Strip that away and the real problem is: a developer about to start task T doesn't know that teammate U already learned the answer. That is a discovery problem, not a storage problem. The plan spends 80% of its surface area (trajectory format, OTel alignment, git store, LFS, replay, federation) on storage and ~5% on the only thing that matters: does the right record surface at the right moment, and does the human trust it?

(1) The niche is real but the moat isn't the format. Entire/GitHub will index sessions. The thing none of them have is a human-approved, non-commit-anchored claim with provenance. That artifact, not `normalized.jsonl`, is the product. Cut raw trajectory capture to "store the native file, don't normalize."

(2) The approval gate is not the adoption risk; the trigger is. Developers don't skip approval because it's hard, they skip it because nothing asks. Fire extraction at the moment of highest salience: when a session ends with no diff. That is the signal that an investigation happened. Build only that detector.

(3) Git-per-team is correct for sync and wrong as the identity of the product. It's plumbing. Stop designing it.

(4) Copilot-CLI-first is feasible but a sunk-cost trap: reverse-engineering `~/.copilot/` state GitHub can change in a point release. Do Claude Code first (documented hooks), ship `hive save` fallback for Copilot.

(5) Sellable only after you have the one number you don't have: hit rate. Records searched / records that changed someone's action.

(6) Biggest failure: you build a library nobody consults. Cut replay, federation, VS Code extension, embeddings, OTel alignment, Codex/Cursor adapters. Keep: no-diff detector, extract, approve, FTS, one MCP tool. Measure one hit in two weeks or stop.

### The Expansionist

The plan is underselling itself.

The investigation record is not a niche, it's the first structured artifact of knowledge work. Entire anchors to commits, Copilot Memory to repos; neither will touch non-committing work because their incentives point at code. Look wider: SRE postmortems, security triage, data-pipeline debugging, "why is this dashboard wrong", compliance evidence trails. Every one is question → evidence → finding → decision with no commit. The schema is a general-purpose evidence format; the engineering team is just the beachhead. Within 12 months vendors will ship "team memory" as a chat-summary blob. They will not ship a schema with evidence pointers back into tool calls. That pointer is the moat: the difference between a note and a citation.

The approval gate is an asset, flip it. Don't frame it as a 30s chore; frame it as authorship. An approved record carries a name, gets cited by other agents ("per Alice's record hiv_01J..."), and becomes visible expertise. Add a weekly "your records were hit N times, saved ~X hours" digest and engineers will compete to save records. Pair with zero-friction auto-capture of drafts so the gate only decides promotion, never capture.

The git-per-team repo is the Trojan horse. Auditable, air-gapped, SSO'd, backed-up for free; a corp security reviewer says yes in one meeting. It makes the format the product. Once ten teams have `.hive/` repos, the paid hub is inevitable: federation, dedup across teams.

Copilot CLI first is a gift: the agent with the biggest enterprise install base and the weakest memory. Hooks mean `similar_before_start` runs automatically. That's the demo: an agent interrupting itself to say "this was already investigated."

Biggest miss: replay is undervalued. "Start from turn 14" is onboarding, training data, and regression-testing of agent behaviour in one feature. Don't cut it; lead with it in Phase 2.

Cut: the VS Code extension until a second team pulls for it. Change: rename "investigation records" to something that sounds like evidence, not notes.

### The Outsider

The biggest thing never explained: why would anyone search? The whole product rests on a developer, about to start a task, pausing to ask "has someone done this?" The plan treats that as given. But in practice this is a cold-start-dependent habit: the hive is empty for months, early searches return nothing, and people stop searching before it has anything worth finding. There is a success metric ("one real hit") but no story for how the first fifty searches that miss don't kill the behavior. That is the single biggest reason this fails, and the plan doesn't list it among the open questions.

The approval gate is backwards. "30s confirming the extract" — I don't believe it. A developer who just finished a two-hour RCA at 6pm will not review a YAML blob with ten fields. The 30s estimate is the author's curse of knowledge. Cut the gate for v1. Capture everything, mark records "unreviewed," let the reader flag bad ones. The cited paper says unreviewed notes preserve mistakes; fine, but zero records preserves zero lessons.

"Per-team git repo" needs a plain-language justification. Who creates the repo, who gets write access, what happens when a trajectory is 40MB, and why is git better than a shared folder plus SQLite? LFS is listed as a shrug. Pick one and defend it.

What I'd need to believe: that repeated investigations are actually findable by text search. "Checkout 502s after 14:00" and "payments timing out" are the same problem with zero overlapping words. Hybrid search is doing the heavy lifting and gets one table row.

Cut: replay/fork, VS Code extension, federation, analytics. Change: make searching free and loud (agent surfaces matches unasked), make saving silent. Then measure whether anyone reads.

### The Executor

Monday morning, the plan dies on question 4, so I checked it.

Concrete facts (verified today): Copilot CLI has 14 hooks, including `sessionStart`, `sessionEnd`, and `agentStop`. `agentStop` hands you `sessionId`, `cwd`, and a `transcriptPath` to a JSONL event log. `sessionEnd` only gives `sessionId` + `reason` — no path. Hooks live in `~/.copilot/hooks/*.json` or `.github/hooks/*.json`, and it also reads `.claude/settings.json`. Entire already ships `entire agent add copilot-cli` which does exactly this capture. On the founder's machine, `~/.copilot/` has config, logs, prompts, instructions — no `session-state/`, because no real session has been run. Phase 0 is therefore not a week; it's a Tuesday: write one `agentStop` hook that copies `transcriptPath` to a folder. Done.

Which means capture is not the product. Entire does it. The only thing you own is the record + "check the hive first" MCP call.

What you do this week: 1. Hook `agentStop` → copy JSONL. One file, zero normalization. 2. `hive save`: pipe the JSONL to whatever model the corp allows, print the YAML, `y/n`. No redaction engine — gitleaks regex, done. 3. Commit to one repo. SQLite FTS5 over the YAML. One MCP tool: `hive.search`. 4. Install for three teammates. Count hits for two weeks.

Cut: replay/fork, normalized.jsonl, OpenTelemetry alignment, VS Code extension, Codex/Cursor adapters, federation, analytics, embeddings. All of it is Phase 5 fantasy until one "we already did this" hit exists.

Approval gate: it kills adoption unless it's `y` in the same terminal, under 10 seconds, and defaults to yes. Make the hook auto-draft on `sessionEnd`; the human only approves on `hive save`.

Biggest failure reason: you spend three months on a format spec and adapters before anyone searches anything. The open question isn't feasibility — it's trivial — it's whether anyone reads records. Build the search in a week and find out.


## Peer reviews
Anonymization mapping: A = The Executor, B = The Outsider, C = The Contrarian, D = The Expansionist, E = The First Principles Thinker.

## Review by The Contrarian
1. Strongest: E. Reframes the problem correctly (discovery, not storage), identifies the one unbuilt mechanism that makes approval survivable (the no-diff detector as salience trigger), and names the single number that determines sellability (hit rate). B's cold-start critique is sharper on its own, but E subsumes it. A deserves credit for verifying Copilot hooks exist (agentStop + transcriptPath), which falsifies C and E's "undocumented internals" objection; weigh A's facts over their assumptions.
2. Biggest blind spot: D. Motivational, not analytical. "Engineers will compete to save records" via a weekly digest is wishful; every gamified knowledge base has died. "Security reviewer says yes in one meeting" ignores C's point that a repo of everything agents saw, regex-redacted, is a six-month ticket. D reads evidence-pointers as a moat when GitHub can add them trivially. Never engages with cold-start or hit rate.
3. All missed: Staleness — a record saying "payments timeout is the Redis pool" is wrong after the fix ships; no invalidation/expiry/code-version linking; stale hits erode trust faster than empty ones. Agent search cost — every similar_before_start call adds latency and tokens to every session for a near-zero hit rate; users will disable it before the hive fills. The corp's existing Copilot/GitHub data agreement may already prohibit exporting transcripts regardless of network boundaries.

## Review by The First Principles Thinker
1. Strongest: E. Strips the question to its root: a discovery problem, not a storage problem. Everyone else argues about the store, the gate, or the adapter; E says those are plumbing and the only unknown is "does the right record surface at the right moment, and does the human trust it?" The no-diff detector is the one genuinely new idea: it names the trigger instead of debating the chore.
2. Biggest blind spot: D. Treats the schema as a moat and the gate as "authorship," but never asks whether anyone will search. Motivation hacks layered on an unproven loop. Assumes corp security says yes "in one meeting."
3. All missed: whether the knowledge is actually reusable. An investigation's value decays: "502s after 14:00" is true until the next deploy. Hive needs an expiry/validity model (record tied to code state, invalidated by relevant commits) or hits will mislead and trust collapses after the first wrong answer. Also: whether the agent, not the human, is the real reader — if so, "readable YAML" is the wrong target; machine-checkable claims are.

## Review by The Expansionist
1. Strongest: A. The only response that verified facts (Copilot CLI's 14 hooks, agentStop giving transcriptPath, Entire already shipping copilot-cli capture). That demolishes C's and E's "reverse-engineer ~/.copilot/" objection and collapses Phase 0 to a day. A names the one thing the founder owns (record + "check the hive first") and a two-week test.
2. Biggest blind spot: C. Treats GitHub org-scoped session memory as the kill shot, but the constraint is "data can't leave the network." GitHub's cloud memory is precisely what this corp cannot adopt. The air-gap is the moat. C also recommends Claude Code first for a team that uses Copilot CLI, discarding the only real users.
3. All missed: The sales number is token spend — duplicate and dead-end investigations have a dollar cost on the agent bill; "three teams re-ran the same 40-minute RCA this month" is a line item CFOs understand. Negative results ("we tried X, it fails because Y") are the highest-value and most invisible records. Cold start is seedable — backfill from existing incident tickets and Slack threads.

## Review by The Outsider
1. Strongest: E. Reframes in a sentence anyone can follow: the developer doesn't know a teammate already learned the answer. Proposes a concrete trigger (session ends with no diff) and names the one number that would prove anything (hit rate). A is a close second for checking the hooks.
2. Biggest blind spot: D. Never explains why an engineer would want authorship. "Your records were hit N times" assumes hits exist. Hand-waves the corp security review. Sells the vision, skips the cold start.
3. All missed: Nobody asked the actual teammates — interview three users this week about when they last redid someone's work. Trust in the reader — a stale or wrong record an agent cites as fact could cost more than no record; nobody addresses expiry, contradiction, "this was true in March." What the corp's lawyers/IT say about storing AI transcripts at all.

## Review by The Executor
1. Strongest: A. Only response that verified anything; collapses Phase 0 to a day and the "moving target" objection to zero; gives a buildable four-step week and a measurable stop condition. E close second on framing but its Copilot claim is wrong.
2. Biggest blind spot: D. Everything requires the hive to already be full and consulted. Never addresses cold start. Tells a solo dev to keep the most expensive feature. A pitch, not a plan.
3. All missed: Zero records needed at search time. hive.similar_before_start can match the in-flight session (hash task prompt + cwd) against teammates' open or just-ended transcripts, pre-extraction. First search hits on day one with no approval, no YAML, no cold start — a weekend's work on top of A's hook. Also: nobody asked whether the corp's allowed LLM endpoint can be called from a hook at all; if not, extraction is manual and the gate debate is moot.


## Chairman's synthesis
## Where the Council Agrees

- **The library-nobody-consults is the failure mode.** A, B, C and E independently landed on the same death spiral: hive is empty, early searches miss, agents and humans stop asking before it fills. This was not in the plan's open questions.
- **Capture is not the product.** A verified that Entire already ships `copilot-cli` capture and that Copilot CLI's `agentStop` hook hands over `transcriptPath`. The only thing the founder owns is the investigation record and the "check the hive first" MCP call.
- **Approval as designed fails; decouple it from capture.** All five, from different directions, converge on silent auto-capture with the gate reduced to a cheap promotion step (A, D, E) or removed outright (B, C). Nobody defends "review a YAML blob at 6pm."
- **Cut the same things.** Four of five cut replay/fork, VS Code extension, federation, analytics, embeddings, OTel alignment, Codex/Cursor adapters. D is the lone holdout on replay.
- **A measurable stop condition.** One real "Alice already did this" hit within two weeks, or stop.

## Where the Council Clashes

- **Copilot CLI first.** C and E call it a reverse-engineering trap and say start with Claude Code. A checked: 14 documented hooks, `agentStop` gives the transcript path, Phase 0 is a Tuesday. Three reviewers sided with A's facts. The chairman does too: C/E's objection was wrong on the facts, and abandoning the only real users to chase a cleaner format is backwards.
- **The gate.** B/C: cut it, unreviewed beats empty. D: it is authorship, gamify it. E: the gate is fine, the trigger is broken — fire extraction when a session ends with no diff. They disagree because they assume different readers: a human needs trust signals, an agent needs provenance, and the plan never says which.
- **The moat.** C: GitHub flips org-scoped session memory in a config change. D and the Expansionist review: this corp cannot adopt GitHub's cloud memory, so the air-gap is the moat. Both are right at different horizons: defensible inside this corp for 12 months, not defensible as a general SaaS.
- **Per-team git.** D: security says yes in one meeting. C: a repo of everything agents saw, regex-redacted, is a six-month ticket. E: it is plumbing, stop designing it. Nobody knows, because nobody asked the security team.

## Blind Spots the Council Caught

- **Staleness.** Three reviewers independently: "502s after 14:00 is the Redis pool" is wrong after the fix ships. No expiry, no link to code state. A stale hit cited as fact destroys trust faster than a miss.
- **Zero-record search** (Executor review). Match the in-flight task prompt plus cwd against teammates' recent raw transcripts, pre-extraction. First hits on day one with no approval, no YAML, no cold start.
- **Can the corp's allowed LLM endpoint be called from a hook at all?** If not, extraction is manual and the gate debate is moot.
- **The corp's GitHub data agreement** may already prohibit exporting Copilot transcripts regardless of network boundary.
- **Per-session search cost.** `similar_before_start` adds latency and tokens to every session at a near-zero hit rate; users will disable it before the hive fills.
- **The sales number is token spend.** Duplicate RCAs are a line item on the agent bill. Negative results ("we tried X, it fails because Y") are the highest-value, most invisible records.
- **Nobody asked the teammates.**

## The Recommendation

Build it, as a discovery tool, not a memory system. E's framing with A's facts.

**CUT:** replay/fork, `normalized.jsonl` and OTel alignment, VS Code extension, Codex/Cursor adapters, federation, analytics, embeddings, a redaction engine (gitleaks regex), and the open-core story until a hit rate exists.

**CHANGE:**
- Capture is silent: one `agentStop` hook copies the native transcript. No normalization.
- Extraction fires only on the no-diff session-end signal. Records are born "draft"; approval is one keypress that promotes, never gates capture.
- Search is loud and free: the agent surfaces matches unasked. Add zero-record matching against teammates' recent transcripts so day-one searches can hit.
- Every record carries the commit SHA and touched files; flag it stale when those change.
- Decide the reader. If it is the agent, target machine-checkable claims, not readable YAML.

**KEEP:** Copilot CLI first. The record schema with evidence pointers back into tool calls. Per-team git as plumbing (stop designing it; ask security and legal one question each). SQLite FTS5. One MCP tool. Two-week stop condition.

## The One Thing to Do First

This week, write the single `agentStop` hook that copies `transcriptPath` to a folder, install it on your three teammates' machines, and count no-diff sessions for two weeks. No extraction, no search, no repo. If investigations-without-commits are not frequent and findable in that raw data, nothing else in the plan matters.

