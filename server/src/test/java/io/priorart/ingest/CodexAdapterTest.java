package io.priorart.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodexAdapterTest {
    private final CodexAdapter adapter = new CodexAdapter();

    private static final String ROLLOUT = String.join("\n",
            "{\"type\":\"session_meta\",\"payload\":{\"session_id\":\"s1\",\"cwd\":\"/w\"}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"developer\",\"content\":[{\"type\":\"input_text\",\"text\":\"<permissions>…</permissions>\"}]}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"<environment_context>cwd</environment_context>\"}]}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"why does the nightly export lock the orders table?\"}]}}",
            "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\"}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"reasoning\",\"encrypted_content\":\"xyz\"}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call\",\"name\":\"exec\",\"input\":\"psql -c 'select * from pg_locks'\"}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"custom_tool_call_output\",\"output\":[{\"type\":\"input_text\",\"text\":\"AccessExclusiveLock on orders\"}]}}",
            "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"The export takes an ACCESS EXCLUSIVE lock; run it on a replica.\"}]}}");

    @Test
    void readsPromptsRepliesAndToolsFromARollout() {
        assertThat(adapter.userPrompts(ROLLOUT)).containsExactly("why does the nightly export lock the orders table?");
        assertThat(adapter.assistantTexts(ROLLOUT)).containsExactly("The export takes an ACCESS EXCLUSIVE lock; run it on a replica.");
        assertThat(adapter.turns(ROLLOUT)).isEqualTo(1);
        assertThat(adapter.renderForExtraction(ROLLOUT, 10_000))
                .contains("USER: why does the nightly export", "TOOL_CALL exec: psql", "TOOL_RESULT: AccessExclusiveLock", "ASSISTANT: The export")
                .doesNotContain("environment_context", "permissions", "xyz");
    }
}
