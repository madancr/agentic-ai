package com.madan.urlshortener.http;

import com.madan.urlshortener.model.UrlEntry;
import com.madan.urlshortener.reliability.RateLimiter;
import com.madan.urlshortener.service.UrlShortenerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/shorten
 * Body:  {"longUrl": "https://example.com/very/long/path", "customAlias": "optional", "ttlSeconds": 3600}
 * Reply: 201 {"shortCode": "abc123", "shortUrl": "http://localhost:8080/abc123", "longUrl": "...", "expiresAt": "..."}
 *
 * Rate limited per client IP (token bucket) -- this is the endpoint that
 * mints new state, so it's the one worth protecting from abuse; redirects
 * are cheap reads and intentionally left unlimited.
 */
public class ShortenHandler implements HttpHandler {

    private final UrlShortenerService service;
    private final RateLimiter rateLimiter;

    public ShortenHandler(UrlShortenerService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed. Use POST.");
            return;
        }

        String clientKey = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
        if (!rateLimiter.tryAcquire(clientKey)) {
            exchange.getResponseHeaders().set("Retry-After",
                    String.valueOf(rateLimiter.estimateRetryAfterSeconds(clientKey)));
            sendError(exchange, 429, "Rate limit exceeded. Slow down and try again shortly.");
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> fields = JsonUtil.parseFlatObject(body);

            String longUrl = fields.get("longUrl");
            String customAlias = fields.get("customAlias");
            Long ttlSeconds = null;
            if (fields.containsKey("ttlSeconds") && !fields.get("ttlSeconds").isBlank()
                    && !"null".equals(fields.get("ttlSeconds"))) {
                ttlSeconds = Long.parseLong(fields.get("ttlSeconds"));
            }

            UrlEntry entry = service.shorten(longUrl, customAlias, ttlSeconds);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("shortCode", entry.getShortCode());
            response.put("shortUrl", service.toShortUrl(entry.getShortCode()));
            response.put("longUrl", entry.getLongUrl());
            response.put("createdAt", entry.getCreatedAt().toString());
            response.put("expiresAt", entry.getExpiresAt() == null ? null : entry.getExpiresAt().toString());

            sendJson(exchange, 201, JsonUtil.toJson(response));

        } catch (UrlShortenerService.InvalidUrlException e) {
            sendError(exchange, 400, e.getMessage());
        } catch (UrlShortenerService.AliasAlreadyExistsException e) {
            sendError(exchange, 409, e.getMessage());
        } catch (NumberFormatException e) {
            sendError(exchange, 400, "ttlSeconds must be a number");
        } catch (Exception e) {
            sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", message);
        err.put("timestamp", Instant.now().toString());
        Object requestId = exchange.getAttribute("requestId");
        if (requestId != null) {
            err.put("requestId", requestId);
        }
        sendJson(exchange, status, JsonUtil.toJson(err));
    }
}
