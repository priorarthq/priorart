package io.priorart.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Codex CLI rollout (~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl): one JSON object per line. The conversation is in
 * {"type":"response_item","payload":{"type":"message","role":"user"|"assistant","content":[{"type":"input_text"|"output_text","text":…}]}};
 * tool calls are payload types custom_tool_call / function_call and their *_output. Developer messages and text Codex
 * injects into the user role (environment context, instructions: it starts with "<") are not the user's prompts.
 */
@Component
public class CodexAdapter implements TranscriptAdapter {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String agent() { return "codex"; }

    @Override
    public List<String> userPrompts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : items(raw)) if (isMessage(p, "user")) for (String t : texts(p)) if (!t.startsWith("<")) out.add(t);
        if (out.isEmpty())   // older rollouts also carry the prompt as an event
            for (JsonNode n : lines(raw))
                if ("event_msg".equals(n.path("type").asText()) && "user_message".equals(n.path("payload").path("type").asText())) {
                    String t = n.path("payload").path("message").asText("").trim();
                    if (!t.isEmpty() && !t.startsWith("<")) out.add(t);
                }
        return out;
    }

    @Override
    public List<String> assistantTexts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : items(raw)) if (isMessage(p, "assistant")) out.addAll(texts(p));
        return out;
    }

    @Override
    public int turns(String raw) {
        int t = 0;
        for (JsonNode p : items(raw)) if (isMessage(p, "assistant")) t++;
        return t;
    }

    @Override
    public String renderForExtraction(String raw, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int turn = 0;
        for (JsonNode p : items(raw)) {
            String type = p.path("type").asText();
            if (isMessage(p, "user")) {
                List<String> prompts = texts(p).stream().filter(t -> !t.startsWith("<")).toList();
                if (prompts.isEmpty()) continue;
                turn++;
                for (String t : prompts) append(sb, turn, "USER", t);
            } else if (isMessage(p, "assistant")) {
                for (String t : texts(p)) append(sb, turn, "ASSISTANT", t);
            } else if (type.equals("custom_tool_call") || type.equals("function_call")) {
                append(sb, turn, "TOOL_CALL " + p.path("name").asText(), p.has("input") ? p.path("input").asText() : p.path("arguments").asText());
            } else if (type.endsWith("_call_output")) {
                append(sb, turn, "TOOL_RESULT", output(p.path("output")));
            }
            if (sb.length() > maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) + "\n[truncated]" : sb.toString();
    }

    private static boolean isMessage(JsonNode p, String role) {
        return "message".equals(p.path("type").asText()) && role.equals(p.path("role").asText());
    }

    private static List<String> texts(JsonNode p) {
        List<String> out = new ArrayList<>();
        JsonNode c = p.path("content");
        if (c.isTextual() && !c.asText().isBlank()) out.add(c.asText().trim());
        else if (c.isArray()) for (JsonNode b : c) if (b.path("text").isTextual() && !b.path("text").asText().isBlank()) out.add(b.path("text").asText().trim());
        return out;
    }

    private static String output(JsonNode o) {
        if (o.isTextual()) return o.asText();
        if (o.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : o) sb.append(b.path("text").isTextual() ? b.path("text").asText() : b.toString()).append('\n');
            return sb.toString();
        }
        return o.path("content").isTextual() ? o.path("content").asText() : o.toString();
    }

    private void append(StringBuilder sb, int turn, String role, String text) {
        if (text == null || text.isBlank()) return;
        String t = text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
        sb.append("[turn ").append(turn).append("] ").append(role).append(": ").append(t).append("\n\n");
    }

    /** The payloads of the response_item lines, in order. */
    private List<JsonNode> items(String raw) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : lines(raw)) if ("response_item".equals(n.path("type").asText())) out.add(n.path("payload"));
        return out;
    }

    private List<JsonNode> lines(String raw) {
        List<JsonNode> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            if (line.isBlank()) continue;
            try { out.add(mapper.readTree(line)); } catch (Exception ignored) {}
        }
        return out;
    }
}
