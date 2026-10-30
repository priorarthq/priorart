package io.priorart.ingest;

import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CopilotCliAdapterTest {
    private final CopilotCliAdapter adapter = new CopilotCliAdapter();

    @Test
    void readsTheSessionEventLog() {
        String log = String.join("\n",
                "{\"type\":\"session.start\",\"data\":{\"sessionId\":\"s1\"}}",
                "{\"type\":\"user.message\",\"data\":{\"content\":\"why does checkout return 502 at 02:00?\"}}",
                "{\"type\":\"assistant.message\",\"data\":{\"content\":\"Let me check the ingress logs.\"}}",
                "{\"type\":\"tool.execution_start\",\"data\":{\"toolName\":\"bash\"}}",
                "{\"type\":\"tool.execution_complete\",\"data\":{\"result\":{\"content\":\"upstream timed out\"}}}",
                "{\"type\":\"assistant.message\",\"data\":{\"content\":\"The nightly export locks the orders table.\"}}");
        assertThat(adapter.userPrompts(log)).containsExactly("why does checkout return 502 at 02:00?");
        assertThat(adapter.turns(log)).isEqualTo(2);
        assertThat(adapter.assistantTexts(log)).containsExactly("Let me check the ingress logs.", "The nightly export locks the orders table.");
        assertThat(adapter.renderForExtraction(log, 10_000)).contains("upstream timed out").doesNotContain("session.start");
    }

    @Test
    void readsChatStyleLines() {
        String chat = "{\"role\":\"user\",\"content\":\"is the cache key wrong?\"}\n{\"role\":\"assistant\",\"content\":\"Yes: it ignores the locale.\"}";
        assertThat(adapter.userPrompts(chat)).isEqualTo(List.of("is the cache key wrong?"));
        assertThat(adapter.turns(chat)).isEqualTo(1);
    }
}
