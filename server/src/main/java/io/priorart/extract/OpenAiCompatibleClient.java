package io.priorart.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;

@Component
@EnableConfigurationProperties(ExtractProperties.class)
public class OpenAiCompatibleClient implements LlmClient {
    private final ExtractProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient http;

    public OpenAiCompatibleClient(ExtractProperties props) {
        this.props = props;
        this.http = RestClient.builder().baseUrl(props.baseUrl()).build();
    }

    @Override public boolean enabled() { return "openai".equalsIgnoreCase(props.provider()); }

    @Override
    public String complete(String system, String user) {
        if (!enabled()) return null;
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        String json;
        try { json = mapper.writeValueAsString(body); }
        catch (Exception e) { throw new IllegalStateException(e); }
        // Send a plain string so the request carries Content-Length; many corporate gateways reject chunked bodies.
        String resp = http.post().uri("/chat/completions")
                .header("Authorization", "Bearer " + props.apiKey())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(json).retrieve().body(String.class);
        try {
            JsonNode n = mapper.readTree(resp);
            return n.path("choices").path(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new IllegalStateException("bad LLM response: " + resp, e);
        }
    }
}
