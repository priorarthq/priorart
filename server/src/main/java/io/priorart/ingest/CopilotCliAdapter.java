package io.priorart.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Copilot CLI transcript at agentStop.transcriptPath. The format is not documented by GitHub, so this adapter is
 * deliberately defensive: it walks every JSON object (JSONL or a single JSON document) and picks up anything that
 * looks like a user prompt or an assistant turn. Two shapes are understood: chat-style {"role":"user","content":…}
 * and Copilot's session event log {"type":"user.message","data":{"content":…}} / "assistant.message" /
 * "tool.execution_complete". Tighten once a real transcript has been inspected.
 */
@Component
public class CopilotCliAdapter implements TranscriptAdapter {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String agent() { return "copilot-cli"; }

    @Override
    public List<String> userPrompts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : nodes(raw)) {
            String role = role(n);
            if (role == null) continue;
            if (role.equalsIgnoreCase("user") || role.toLowerCase().contains("prompt")) {
                String t = textOf(n);
                if (t != null && !t.isBlank()) out.add(t.trim());
            }
        }
        if (out.isEmpty() && !raw.isBlank()) out.add(raw.length() > 2000 ? raw.substring(0, 2000) : raw);
        return out;
    }

    @Override
    public String renderForExtraction(String raw, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int turn = 0;
        for (JsonNode n : nodes(raw)) {
            String role = role(n);
            String t = textOf(n);
            if (role == null || t == null || t.isBlank()) continue;
            turn++;
            sb.append("[turn ").append(turn).append("] ").append(role.toUpperCase()).append(": ")
              .append(t.length() > 4000 ? t.substring(0, 4000) + "…" : t).append("\n\n");
            if (sb.length() > maxChars) break;
        }
        if (sb.isEmpty()) sb.append(raw, 0, Math.min(raw.length(), maxChars));
        return sb.length() > maxChars ? sb.substring(0, maxChars) + "\n[truncated]" : sb.toString();
    }

    @Override
    public List<String> assistantTexts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : nodes(raw)) {
            String role = role(n);
            String t = textOf(n);
            if (role != null && role.equalsIgnoreCase("assistant") && t != null && !t.isBlank()) out.add(t.trim());
        }
        return out;
    }

    @Override
    public int turns(String raw) {
        int t = 0;
        for (JsonNode n : nodes(raw)) {
            String role = role(n);
            if (role != null && role.equalsIgnoreCase("assistant")) t++;
        }
        return t;
    }

    private List<JsonNode> nodes(String raw) {
        List<JsonNode> out = new ArrayList<>();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") || (trimmed.startsWith("{") && !trimmed.contains("\n{"))) {
            try {
                JsonNode root = mapper.readTree(trimmed);
                if (root.isArray()) root.forEach(out::add);
                else { // single doc: look for a messages/events/turns array
                    for (String k : List.of("messages", "events", "turns", "history", "items")) {
                        if (root.path(k).isArray()) { root.path(k).forEach(out::add); return out; }
                    }
                    out.add(root);
                }
                return out;
            } catch (Exception ignored) {}
        }
        for (String line : raw.split("\n")) {
            if (line.isBlank()) continue;
            try { out.add(mapper.readTree(line)); } catch (Exception ignored) {}
        }
        return out;
    }

    /** "user", "assistant", "tool" or another raw value; "user.message" / "assistant.message" events map to their role. */
    private String role(JsonNode n) {
        String r = firstText(n, "role", "type", "kind", "speaker");
        if (r == null) return null;
        String l = r.toLowerCase(java.util.Locale.ROOT);
        if (l.startsWith("user.") || l.equals("user_message")) return "user";
        if (l.startsWith("assistant.") || l.equals("assistant_message")) return "assistant";
        if (l.startsWith("tool.")) return l.endsWith("complete") || l.endsWith("result") ? "tool" : null;   // starts are noise
        if (l.startsWith("session.") || l.endsWith(".delta")) return null;
        return r;
    }

    private String firstText(JsonNode n, String... keys) {
        for (String k : keys) if (n.path(k).isTextual()) return n.path(k).asText();
        JsonNode m = n.path("message");
        if (m.isObject()) for (String k : keys) if (m.path(k).isTextual()) return m.path(k).asText();
        return null;
    }

    private String textOf(JsonNode n) {
        JsonNode data = n.path("data");
        if (data.isObject()) {   // event log: the payload is under "data" (a tool result under data.result)
            String t = textOf(data);
            if (t != null) return t;
            if (data.path("result").isObject()) return textOf(data.path("result"));
        }
        for (String k : List.of("content", "text", "prompt", "message")) {
            JsonNode v = n.path(k);
            if (v.isTextual()) return v.asText();
            if (v.isObject() && v.path("content").isTextual()) return v.path("content").asText();
            if (v.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode b : v) {
                    if (b.isTextual()) sb.append(b.asText()).append('\n');
                    else if (b.path("text").isTextual()) sb.append(b.path("text").asText()).append('\n');
                }
                if (!sb.isEmpty()) return sb.toString();
            }
        }
        return null;
    }
}
