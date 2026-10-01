package com.madan.urlshortener.agent.tools;

import com.madan.urlshortener.agent.json.Json;

import java.util.Map;

/**
 * What goes back to the model after a tool call. {@code isError} tells the model the call failed so it
 * can explain or try something else; {@code data} is serialized to JSON for the model to read.
 */
public record ToolResult(boolean isError, Map<String, Object> data) {

    public static ToolResult ok(Map<String, Object> data) {
        return new ToolResult(false, data);
    }

    public static ToolResult error(String code, String message) {
        return new ToolResult(true, Map.of("error", Map.of("code", code, "message", message)));
    }

    public String toJson() {
        return Json.write(data);
    }
}
