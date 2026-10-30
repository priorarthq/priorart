package io.priorart.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** Claude Code JSONL: one JSON object per line, type=user|assistant, message.content is a string or an array of blocks. */
@Component
public class ClaudeCodeAdapter implements TranscriptAdapter {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String agent() { return "claude-code"; }

    @Override
    public List<String> userPrompts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : lines(raw)) {
            if (!"user".equals(n.path("type").asText())) continue;
            if (n.path("isSidechain").asBoolean(false)) continue;
            JsonNode content = n.path("message").path("content");
            if (content.isTextual()) {
                String t = content.asText().trim();
                if (!t.isEmpty() && !t.startsWith("<")) out.add(t); // skip injected system-ish blocks
            } else if (content.isArray()) {
                for (JsonNode b : content) {
                    if ("text".equals(b.path("type").asText())) {
                        String t = b.path("text").asText().trim();
                        if (!t.isEmpty() && !t.startsWith("<")) out.add(t);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public String renderForExtraction(String raw, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int turn = 0;
        for (JsonNode n : lines(raw)) {
            String type = n.path("type").asText();
            if (!type.equals("user") && !type.equals("assistant")) continue;
            JsonNode content = n.path("message").path("content");
            turn++;
            if (content.isTextual()) {
                append(sb, turn, type.toUpperCase(), content.asText());
            } else if (content.isArray()) {
                for (JsonNode b : content) {
                    switch (b.path("type").asText()) {
                        case "text" -> append(sb, turn, type.toUpperCase(), b.path("text").asText());
                        case "tool_use" -> append(sb, turn, "TOOL_CALL " + b.path("name").asText(), b.path("input").toString());
                        case "tool_result" -> append(sb, turn, "TOOL_RESULT", b.path("content").isTextual()
                                ? b.path("content").asText() : b.path("content").toString());
                        default -> {}
                    }
                }
            }
            if (sb.length() > maxChars) break;
        }
        return sb.length() > maxChars ? sb.substring(0, maxChars) + "\n[truncated]" : sb.toString();
    }

    @Override
    public List<String> assistantTexts(String raw) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : lines(raw)) {
            if (!"assistant".equals(n.path("type").asText()) || n.path("isSidechain").asBoolean(false)) continue;
            JsonNode content = n.path("message").path("content");
            if (content.isTextual() && !content.asText().isBlank()) out.add(content.asText().trim());
            else if (content.isArray())
                for (JsonNode b : content)
                    if ("text".equals(b.path("type").asText()) && !b.path("text").asText().isBlank()) out.add(b.path("text").asText().trim());
        }
        return out;
    }

    @Override
    public int turns(String raw) {
        int t = 0;
        for (JsonNode n : lines(raw)) if ("assistant".equals(n.path("type").asText())) t++;
        return t;
    }

    private void append(StringBuilder sb, int turn, String role, String text) {
        if (text == null || text.isBlank()) return;
        String t = text.length() > 4000 ? text.substring(0, 4000) + "…" : text;
        sb.append("[turn ").append(turn).append("] ").append(role).append(": ").append(t).append("\n\n");
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
