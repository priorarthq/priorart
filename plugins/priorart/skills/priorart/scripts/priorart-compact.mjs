// Transcript compaction for the Prior Art capture hook (separate module so it can be tested on real transcripts).
//
// Long sessions reach 100+ MB, but what extraction and search use is the conversation: prompts, replies, and the gist
// of each tool call. Compaction works by kind of content and keeps the agent's own format (one JSON object per line):
//   - screenshots / embedded images      → placeholder (never stored: size, and pixels can't be checked for secrets)
//   - prompts and replies (text blocks)  → kept, up to TEXT_LIMIT each (head + tail)
//   - tool inputs / tool outputs / thinking / anything else → trimmed to a few hundred characters per value
//   - Claude Code bookkeeping lines (file snapshots, queue ops, attachments, artifact ledgers) → dropped, and message
//     lines keep only the fields that carry conversation (type, message, timestamp, sidechain/meta flags)
//   - Codex bookkeeping (token counts, turn settings, item-completed echoes, encrypted reasoning) → dropped; message
//     text in response_item payloads is kept like any other prompt or reply
// Only if the result is still over maxTotal are the oldest middle lines elided (start and most recent part kept).
// Small transcripts without large values are returned unchanged.

const TEXT_LIMIT = 20_000;
const LIMITS = { tool_use: 400, tool_result: 1_000, thinking: 500, other: 800 };
export const MAX_TOTAL = 15 * 1024 * 1024;

const CLAUDE_DROP_LINE = (t) => ["file-history-snapshot", "queue-operation", "last-prompt", "attachment"].includes(t) || /^artifact-/.test(t ?? "");
const CLAUDE_KEEP_KEYS = new Set(["type", "message", "timestamp", "isSidechain", "isMeta", "subtype", "summary", "content", "level"]);
// Codex rollouts: event_msg lines repeat what response_item lines already hold, or are counters and settings.
const CODEX_DROP_LINE = (d) => ["turn_context", "token_usage_record", "world_state"].includes(d.type)
  || (d.type === "event_msg" && !["user_message", "agent_message"].includes(d.payload?.type))
  || (d.type === "response_item" && d.payload?.type === "reasoning");
const CODEX_LIMIT = { custom_tool_call: 400, function_call: 400, custom_tool_call_output: 1_000, function_call_output: 1_000 };
const CLAUDE_KEEP_MESSAGE_KEYS = new Set(["role", "content", "model", "stop_reason"]);

export function compact(text, { maxTotal = MAX_TOTAL, agent = "" } = {}) {
  const all = text.split("\n").filter((l) => l.trim());
  if (text.length <= 2 * 1024 * 1024 && all.every((l) => l.length <= TEXT_LIMIT)) return { text, note: "" };
  const claude = agent === "claude-code", codex = agent === "codex";
  const stats = { images: 0, trimmed: 0, droppedLines: 0, elided: 0 };

  const trim = (str, limit) => {
    if (str.length <= limit) return str;
    stats.trimmed++;
    return `${str.slice(0, limit / 2)}…[${str.length - limit} chars elided by Prior Art]…${str.slice(-limit / 2)}`;
  };
  const isImage = (v) => v && typeof v === "object" && ((v.source && v.source.type === "base64") || v.type === "image");
  const shrink = (v, limit) => {
    if (typeof v === "string") return trim(v, limit);
    if (Array.isArray(v)) return v.map((x) => shrink(x, limit));
    if (v && typeof v === "object") {
      if (isImage(v)) { stats.images++; return { type: "image", priorart: "screenshot elided" }; }
      const o = {};
      for (const [k, x] of Object.entries(v)) if (k !== "signature") o[k] = shrink(x, limit); // thinking signatures: opaque blobs
      return o;
    }
    return v;
  };
  const block = (b) => {
    if (!b || typeof b !== "object") return shrink(b, LIMITS.other);
    if (b.type === "text" && typeof b.text === "string") return { ...b, text: trim(b.text, TEXT_LIMIT) };
    return shrink(b, LIMITS[b.type] ?? LIMITS.other);
  };
  const content = (c) => (typeof c === "string" ? trim(c, TEXT_LIMIT) : Array.isArray(c) ? c.map(block) : shrink(c, LIMITS.other));
  const pick = (o, keys) => Object.fromEntries(Object.entries(o).filter(([k]) => keys.has(k)));

  let lines = [];
  for (const l of all) {
    let d;
    try { d = JSON.parse(l); } catch { lines.push(trim(l, LIMITS.other)); continue; }
    if (!d || typeof d !== "object" || Array.isArray(d)) { lines.push(JSON.stringify(shrink(d, LIMITS.other))); continue; }
    if (claude && CLAUDE_DROP_LINE(d.type)) { stats.droppedLines++; continue; }
    if (codex && CODEX_DROP_LINE(d)) { stats.droppedLines++; continue; }
    if (codex && d.type === "response_item" && d.payload && typeof d.payload === "object") {
      const p = d.payload;
      const kept = p.type === "message"
        ? { ...shrink({ ...p, content: undefined }, LIMITS.other), content: Array.isArray(p.content)
            ? p.content.map((b) => (b && typeof b.text === "string" ? { ...b, text: trim(b.text, TEXT_LIMIT) } : shrink(b, LIMITS.other)))
            : content(p.content) }
        : shrink(p, CODEX_LIMIT[p.type] ?? LIMITS.other);
      lines.push(JSON.stringify({ ...shrink({ ...d, payload: undefined }, LIMITS.other), payload: kept }));
      continue;
    }
    if (d.message && typeof d.message === "object" && "content" in d.message) {
      const rest = claude ? pick(d, CLAUDE_KEEP_KEYS) : d;
      const meta = claude ? pick(d.message, CLAUDE_KEEP_MESSAGE_KEYS) : { ...d.message };
      delete rest.message; delete meta.content;
      lines.push(JSON.stringify({ ...shrink(rest, LIMITS.other), message: { ...shrink(meta, LIMITS.other), content: content(d.message.content) } }));
    } else {
      lines.push(JSON.stringify(shrink(claude ? pick(d, CLAUDE_KEEP_KEYS) : d, LIMITS.other)));
    }
  }

  const size = (ls) => ls.reduce((n, l) => n + l.length + 1, 0);
  if (size(lines) > maxTotal) {
    const head = [], tail = [];
    let budgetHead = maxTotal * 0.2, budgetTail = maxTotal * 0.8 - 200;
    for (const l of lines) { if (l.length + 1 > budgetHead) break; head.push(l); budgetHead -= l.length + 1; }
    for (let i = lines.length - 1; i >= head.length; i--) { if (lines[i].length + 1 > budgetTail) break; tail.unshift(lines[i]); budgetTail -= lines[i].length + 1; }
    stats.elided = lines.length - head.length - tail.length;
    lines = [...head, JSON.stringify({ type: "priorart-elided", lines: stats.elided }), ...tail];
  }
  return {
    text: lines.join("\n") + "\n",
    note: ` (compacted from ${text.length}: ${stats.images} images, ${stats.trimmed} values trimmed, ${stats.droppedLines} bookkeeping lines dropped, ${stats.elided} lines elided)`,
  };
}
