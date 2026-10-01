package com.madan.urlshortener.agent.tools;

import java.util.List;
import java.util.Map;

/** Tool: create a short link via POST /api/shorten. */
public class ShortenUrlTool implements Tool {

    private final ShortenerApiClient api;

    public ShortenUrlTool(ShortenerApiClient api) {
        this.api = api;
    }

    @Override
    public String name() {
        return "shorten_url";
    }

    @Override
    public String description() {
        return "Create a short link for a long http(s) URL. Optionally choose a custom alias (3-32 letters, digits, "
                + "'_' or '-') and a time-to-live in seconds. Shortening the same URL again without an alias returns "
                + "the existing short link.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of("type", "string", "maxLength", 2048,
                                "description", "The long URL to shorten (http or https)."),
                        "alias", Map.of("type", "string", "pattern", "^[A-Za-z0-9_-]{3,32}$",
                                "description", "Optional custom alias, e.g. 'spring-launch'."),
                        "ttl_seconds", Map.of("type", "integer", "minimum", 60, "maximum", 31536000,
                                "description", "Optional lifetime of the link in seconds.")),
                "required", List.of("url"),
                "additionalProperties", false);
    }

    /** Custom aliases are a public, first-come namespace (brand risk), so a human confirms them. */
    @Override
    public String confirmationReason(Map<String, Object> input) {
        Object alias = input.get("alias");
        return alias == null ? null
                : "Create a PUBLIC custom alias '" + alias + "' pointing to " + input.get("url");
    }

    @Override
    public ToolResult execute(Map<String, Object> input) throws Exception {
        Long ttl = input.get("ttl_seconds") == null ? null : ((Number) input.get("ttl_seconds")).longValue();
        return ApiResults.from(api.shorten((String) input.get("url"), (String) input.get("alias"), ttl));
    }
}
