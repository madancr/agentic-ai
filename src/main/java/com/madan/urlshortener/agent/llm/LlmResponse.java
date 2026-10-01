package com.madan.urlshortener.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One model turn. {@code content} is a list of blocks, each either
 * {"type":"text","text":...} or {"type":"tool_use","id":...,"name":...,"input":{...}}.
 * {@code stopReason} is "tool_use" when the model wants tools run, "end_turn" when it is done.
 */
public record LlmResponse(List<Map<String, Object>> content, String stopReason, long inputTokens, long outputTokens) {

    public boolean wantsTools() {
        return "tool_use".equals(stopReason);
    }

    public List<Map<String, Object>> toolUses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> block : content) {
            if ("tool_use".equals(block.get("type"))) out.add(block);
        }
        return out;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) sb.append(block.get("text"));
        }
        return sb.toString();
    }
}
