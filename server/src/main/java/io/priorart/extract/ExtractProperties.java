package io.priorart.extract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param quietSeconds a no-diff session is extracted once its transcript has stopped changing for this long
 *                     (Copilot re-sends the transcript after every turn).
 */
@ConfigurationProperties(prefix = "priorart.extract")
public record ExtractProperties(String provider, String baseUrl, String apiKey, String model, int maxTranscriptChars,
                                int quietSeconds) {}
