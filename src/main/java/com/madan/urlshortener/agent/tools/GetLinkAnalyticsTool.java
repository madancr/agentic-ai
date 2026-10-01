package com.madan.urlshortener.agent.tools;

import java.util.List;
import java.util.Map;

/** Tool: traffic analytics via GET /api/analytics/{code} (last 24h / 7d, daily breakdown, top referrers). */
public class GetLinkAnalyticsTool implements Tool {

    private final ShortenerApiClient api;

    public GetLinkAnalyticsTool(ShortenerApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "get_link_analytics";
    }

    @Override
    public String description() {
        return "Get traffic analytics for a short link: clicks in the last 24 hours and 7 days, clicks per day, and "
                + "the top 5 referrers sending traffic to it.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{1,32}$",
                                "description", "Short code or alias.")),
                "required", List.of("code"),
                "additionalProperties", false);
    }

    @Override
    public ToolResult execute(Map<String, Object> input) throws Exception {
        return ApiResults.from(api.analytics((String) input.get("code")));
    }
}
