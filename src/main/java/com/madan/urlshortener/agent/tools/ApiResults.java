package com.madan.urlshortener.agent.tools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns an API response into a ToolResult for the model.
 *
 * The service returns errors as {"error": "message", "timestamp": ..., "requestId": ...}. Here the HTTP
 * status becomes a stable error code the model can reason about (e.g. 409 -> alias_taken), and the
 * service's X-Request-Id is attached to every result so an agent action can be traced to the exact
 * server access-log line.
 */
final class ApiResults {

    private ApiResults() {
    }

    static ToolResult from(ShortenerApiClient.ApiResponse resp) {
        if (resp.ok()) {
            Map<String, Object> data = new LinkedHashMap<>(resp.body());
            if (resp.requestId() != null) data.put("requestId", resp.requestId());
            return ToolResult.ok(data);
        }
        String code = switch (resp.status()) {
            case 400 -> "invalid_request";
            case 404 -> "not_found";
            case 409 -> "alias_taken";
            case 410 -> "expired";
            case 429 -> "rate_limited";
            default -> "service_error_" + resp.status();
        };
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", code);
        err.put("message", String.valueOf(resp.body().getOrDefault("error", "HTTP " + resp.status())));
        err.put("httpStatus", (long) resp.status());
        if (resp.requestId() != null) err.put("requestId", resp.requestId());
        return new ToolResult(true, Map.of("error", err));
    }
}
