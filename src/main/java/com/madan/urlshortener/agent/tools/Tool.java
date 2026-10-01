package com.madan.urlshortener.agent.tools;

import java.util.Map;

/**
 * A capability the LLM is allowed to use. The model only ever sees name, description and the
 * JSON schema of the input; it asks for a call, and OUR code decides whether and how to run it.
 */
public interface Tool {

    /** Name the model uses to call the tool, e.g. "shorten_url". */
    String name();

    /** Plain-English description: this is what the model reads to decide when to use the tool. */
    String description();

    /** JSON Schema for the input (the model's arguments are validated against it before execution). */
    Map<String, Object> inputSchema();

    /** Runs the tool. Only called after the guardrails have allowed it. */
    ToolResult execute(Map<String, Object> input) throws Exception;

    /**
     * High-impact calls need a human "yes" before they run. Returns the reason to show the human,
     * or null if no confirmation is needed.
     */
    default String confirmationReason(Map<String, Object> input) {
        return null;
    }
}
