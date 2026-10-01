package com.madan.urlshortener.agent.llm;

import com.madan.urlshortener.agent.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calls the Claude Messages API with tool use (plain java.net.http, no SDK).
 *
 * Retries rate-limit/overload errors (429, 5xx, 529) with exponential backoff, a bounded number of times.
 */
public class ClaudeLlmClient implements LlmClient {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final Set<Integer> RETRYABLE = Set.of(408, 429, 500, 502, 503, 504, 529);
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String model;

    public ClaudeLlmClient(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String name() {
        return "claude:" + model;
    }

    /** Builds the exact JSON body sent to the API (public so it can be unit-tested without a network). */
    public String buildRequestBody(String systemPrompt, List<Map<String, Object>> messages,
                                   List<Map<String, Object>> tools) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 1024);
        body.put("system", systemPrompt);
        body.put("tools", tools);
        body.put("messages", messages);
        return Json.write(body);
    }

    @Override
    @SuppressWarnings("unchecked")
    public LlmResponse complete(String systemPrompt, List<Map<String, Object>> messages,
                                List<Map<String, Object>> tools) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(systemPrompt, messages, tools)))
                .build();

        long backoffMs = 1000;
        for (int attempt = 1; ; attempt++) {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, Object> data = Json.parseObject(resp.body());
                Map<String, Object> usage = (Map<String, Object>) data.getOrDefault("usage", Map.of());
                return new LlmResponse(
                        (List<Map<String, Object>>) data.get("content"),
                        (String) data.get("stop_reason"),
                        ((Number) usage.getOrDefault("input_tokens", 0L)).longValue(),
                        ((Number) usage.getOrDefault("output_tokens", 0L)).longValue());
            }
            if (!RETRYABLE.contains(resp.statusCode()) || attempt == MAX_ATTEMPTS) {
                throw new IOException("Claude API error " + resp.statusCode() + ": " + resp.body());
            }
            Thread.sleep(backoffMs);
            backoffMs *= 2;
        }
    }
}
