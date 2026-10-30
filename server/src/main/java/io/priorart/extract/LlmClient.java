package io.priorart.extract;

/** Minimal chat-completion client. Corps swap the endpoint via config; the server never hard-wires a vendor. */
public interface LlmClient {
    /** Returns the raw assistant text for a system+user prompt pair, or null if no provider is configured. */
    String complete(String system, String user);
    boolean enabled();
}
