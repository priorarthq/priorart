package io.priorart.ingest;

import java.util.List;

/** Reads an agent's native transcript. One implementation per agent. Never normalizes; only extracts what search and extraction need. */
public interface TranscriptAdapter {
    String agent();

    /** Ordered user prompts (human turns only, not tool results). */
    List<String> userPrompts(String raw);

    /** Compact plain-text rendering of the whole session for the extraction model. */
    String renderForExtraction(String raw, int maxChars);

    int turns(String raw);

    /** The assistant's text replies in order (no tool calls), for building a draft without an extraction model. */
    List<String> assistantTexts(String raw);
}
