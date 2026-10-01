package com.madan.urlshortener.agent.tools;

import java.util.List;
import java.util.Map;

/** Tool: where a link points + lifetime click count, via GET /api/stats/{code}. Read-only: never counts a click. */
public class GetLinkStatsTool implements Tool {

    private final ShortenerApiClient api;

    public GetLinkStatsTool(ShortenerApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "get_link_stats";
    }

    @Override
    public String description() {
        return "Look up a short link without visiting it: its destination URL, creation and expiry time, and total "
                + "lifetime click count. Use the short code or alias (the part after the slash).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,32}$",
                                "description", "Short code or alias, e.g. '4c92' or 'spring-launch'.")),
                "required", List.of("code"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(Map<String, Object> input) throws Exception {
        return ApiResults.from(api.stats((String) input.get("code")));
    }
}
