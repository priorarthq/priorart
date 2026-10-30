// node --test 'tests/skill/*.test.mjs'   — compaction keeps the conversation and drops the bulk.
import { test } from "node:test";
import assert from "node:assert/strict";
import { compact } from "../../plugins/priorart/skills/priorart/scripts/priorart-compact.mjs";

const line = (o) => JSON.stringify(o);
const user = (text) => line({ type: "user", uuid: "u", cwd: "/x", message: { role: "user", content: text } });
const assistantText = (text) => line({ type: "assistant", uuid: "a", message: { id: "m", role: "assistant", usage: { input_tokens: 1 }, content: [{ type: "text", text }] } });
const toolUse = (input) => line({ type: "assistant", message: { role: "assistant", content: [{ type: "tool_use", id: "t", name: "Write", input }] } });
const toolResult = (content, extra = {}) => line({ type: "user", toolUseResult: { stdout: content }, ...extra, message: { role: "user", content: [{ type: "tool_result", tool_use_id: "t", content }] } });
const screenshot = () => line({ type: "user", message: { role: "user", content: [{ type: "tool_result", tool_use_id: "s", content: [{ type: "image", source: { type: "base64", media_type: "image/png", data: "A".repeat(300_000) } }] }] } });

test("small transcripts are returned unchanged", () => {
  const t = user("why is checkout slow") + "\n" + assistantText("looking") + "\n";
  assert.deepEqual(compact(t, { agent: "claude-code" }), { text: t, note: "" });
});

test("every prompt and reply survives; screenshots, bulk and bookkeeping do not", () => {
  const lines = [];
  for (let i = 0; i < 40; i++) {
    lines.push(user(`prompt ${i}: why does job ${i} fail`));
    lines.push(toolUse({ file_path: `/f${i}.txt`, content: "x".repeat(50_000) }));
    lines.push(toolResult("log line\n".repeat(20_000)));
    lines.push(screenshot());
    lines.push(line({ type: "file-history-snapshot", snapshot: "y".repeat(10_000) }));
    lines.push(assistantText(`reply ${i}: it fails because of lock ${i}`));
  }
  const raw = lines.join("\n") + "\n";
  const { text, note } = compact(raw, { agent: "claude-code" });

  assert.ok(text.length < raw.length / 20, `expected >20x smaller, got ${raw.length} -> ${text.length}`);
  for (let i = 0; i < 40; i++) {
    assert.ok(text.includes(`prompt ${i}: why does job ${i} fail`), `prompt ${i} kept`);
    assert.ok(text.includes(`reply ${i}: it fails because of lock ${i}`), `reply ${i} kept`);
  }
  assert.ok(!text.includes("AAAAAAAAAA"), "no image data");
  assert.ok(text.includes("screenshot elided"), "image placeholder");
  assert.ok(!text.includes("file-history-snapshot"), "bookkeeping lines dropped");
  assert.ok(!text.includes("toolUseResult") && !text.includes('"usage"') && !text.includes('"uuid"'), "redundant fields dropped");
  assert.match(note, /0 lines elided/);
  for (const l of text.trim().split("\n")) JSON.parse(l); // still one JSON object per line
});

test("over the cap, the start and the most recent part are kept", () => {
  const lines = Array.from({ length: 2000 }, (_, i) => assistantText(`reply ${i} ` + "z".repeat(15_000)));
  const { text, note } = compact(lines.join("\n"), { agent: "claude-code", maxTotal: 2_000_000 });
  assert.ok(text.length <= 2_000_000);
  assert.ok(text.includes("reply 0 ") && text.includes("reply 1999 "), "first and last kept");
  assert.ok(text.includes("priorart-elided"));
  assert.doesNotMatch(note, / 0 lines elided/);
});

test("codex rollouts keep prompts and replies, drop bookkeeping", () => {
  const big = "x".repeat(30_000);
  const rollout = [
    { type: "session_meta", payload: { session_id: "s1", base_instructions: { text: big } } },
    { type: "event_msg", payload: { type: "token_count", info: big } },
    { type: "turn_context", payload: { cwd: "/w" } },
    { type: "response_item", payload: { type: "message", role: "user", content: [{ type: "input_text", text: "why is the export slow? " + "y".repeat(5_000) }] } },
    { type: "response_item", payload: { type: "reasoning", encrypted_content: big } },
    { type: "response_item", payload: { type: "custom_tool_call", name: "exec", input: big } },
    { type: "response_item", payload: { type: "custom_tool_call_output", output: [{ type: "input_text", text: big }] } },
    { type: "response_item", payload: { type: "message", role: "assistant", content: [{ type: "output_text", text: "Because of the lock." }] } },
  ].map((o) => JSON.stringify(o)).join("\n");
  const { text } = compact(rollout, { agent: "codex" });
  const lines = text.trim().split("\n").map((l) => JSON.parse(l));
  assert.ok(!lines.some((l) => l.type === "event_msg" || l.type === "turn_context" || l.payload?.type === "reasoning"));
  const user = lines.find((l) => l.payload?.role === "user");
  assert.ok(user.payload.content[0].text.length > 5_000, "the prompt is kept whole");
  assert.equal(lines.find((l) => l.payload?.role === "assistant").payload.content[0].text, "Because of the lock.");
  assert.ok(JSON.stringify(lines.find((l) => l.payload?.type === "custom_tool_call")).length < 2_000, "tool input trimmed");
});
