package com.madan.urlshortener.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * Returns pre-written responses in order (the last one repeats forever). Used to simulate a
 * MISBEHAVING model - one that hallucinates tools, sends bad arguments, or never stops calling
 * tools - to show that the guardrails and step limit contain it.
 */
public class ScriptedLlmClient implements LlmClient {

    private final List<LlmResponse> script;
    private int next = 0;

    public ScriptedLlmClient(List<LlmResponse> script) {
        this.script = script;
    }

    @Override
    public String name() {
        return "scripted";
    }

    @Override
    public LlmResponse complete(String systemPrompt, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools) {
        LlmResponse r = script.get(Math.min(next, script.size() - 1));
        next++;
        return r;
    }

    /** Convenience: a response that asks for one or more tool calls. */
    public static LlmResponse toolCalls(Object... nameInputPairs) {
        List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < nameInputPairs.length; i += 2) {
            blocks.add(Map.of("type", "tool_use", "id", "toolu_scripted_" + i + "_" + System.nanoTime(),
                    "name", nameInputPairs[i], "input", nameInputPairs[i + 1]));
        }
        return new LlmResponse(blocks, "tool_use", 0, 0);
    }
}
