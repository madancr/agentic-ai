package com.madan.urlshortener.agent.llm;

import java.util.List;
import java.util.Map;

/**
 * Anything that can act as the agent's "brain". Messages and tool definitions use the Claude Messages API
 * format, so the real model ({@link ClaudeLlmClient}) and the offline stand-in ({@link OfflineLlmClient})
 * are interchangeable.
 */
public interface LlmClient {

    LlmResponse complete(String systemPrompt, List<Map<String, Object>> messages, List<Map<String, Object>> tools)
            throws Exception;

    String name();
}
