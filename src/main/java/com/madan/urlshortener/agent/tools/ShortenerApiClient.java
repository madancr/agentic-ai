package com.madan.urlshortener.agent.tools;

import com.madan.urlshortener.agent.json.Json;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client for the URL shortener's EXISTING REST API. The agent's tools use this, so the agent talks to
 * the service exactly like any other client - through the public endpoints, subject to the same validation
 * and the same per-IP rate limiter - never by calling UrlShortenerService or the repository directly.
 *
 * Reliability: a 429 from the service's RateLimiter is retried ONCE after honouring Retry-After (capped),
 * so a short burst does not fail the user's request, but the agent can never hammer the service.
 */
public class ShortenerApiClient {

    /** Status, parsed JSON body and the service's X-Request-Id (links agent audit -> server access log). */
    public record ApiResponse(int status, Map<String, Object> body, String requestId) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final long MAX_RETRY_AFTER_SECONDS = 2;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String baseUrl;

    public ShortenerApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** POST /api/shorten  {"longUrl": ..., "customAlias": ..., "ttlSeconds": ...} */
    public ApiResponse shorten(String longUrl, String customAlias, Long ttlSeconds) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("longUrl", longUrl);
        if (customAlias != null) body.put("customAlias", customAlias);
        if (ttlSeconds != null) body.put("ttlSeconds", ttlSeconds);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/shorten"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "url-shortener-agent/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();
        return send(req);
    }

    /** GET /api/stats/{code} - metadata + click count, does NOT count a click. */
    public ApiResponse stats(String code) throws IOException, InterruptedException {
        return send(get("/api/stats/" + encode(code)));
    }

    /** GET /api/analytics/{code} - clicks last 24h/7d, daily breakdown, top referrers. */
    public ApiResponse analytics(String code) throws IOException, InterruptedException {
        return send(get("/api/analytics/" + encode(code)));
    }

    /** Follows a short link the way a browser would (the demo uses this to generate real clicks). */
    public int visit(String code, String referrer) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/" + encode(code))).GET();
        if (referrer != null) b.header("Referer", referrer);
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "url-shortener-agent/1.0")
                .GET()
                .build();
    }

    private ApiResponse send(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            long wait = resp.headers().firstValue("Retry-After").map(Long::parseLong).orElse(1L);
            Thread.sleep(Math.min(wait, MAX_RETRY_AFTER_SECONDS) * 1000);
            resp = http.send(req, HttpResponse.BodyHandlers.ofString()); // one bounded retry only
        }
        Map<String, Object> body = resp.body() == null || resp.body().isBlank()
                ? Map.of() : Json.parseObject(resp.body());
        String requestId = resp.headers().firstValue("X-Request-Id").orElse(null);
        return new ApiResponse(resp.statusCode(), body, requestId);
    }

    private static String encode(String code) {
        return URLEncoder.encode(code, StandardCharsets.UTF_8);
    }
}
