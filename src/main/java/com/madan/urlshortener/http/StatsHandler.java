package com.madan.urlshortener.http;

import com.madan.urlshortener.model.UrlEntry;
import com.madan.urlshortener.service.UrlShortenerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/stats/{code} -> click count and metadata, without consuming a "click".
 */
public class StatsHandler implements HttpHandler {

    private static final String PREFIX = "/api/stats/";

    private final UrlShortenerService service;

    public StatsHandler(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ShortenHandler.sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String code = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";

        if (code.isEmpty()) {
            ShortenHandler.sendError(exchange, 400, "Missing short code in path: /api/stats/{code}");
            return;
        }

        try {
            UrlEntry entry = service.lookup(code);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("shortCode", entry.getShortCode());
            response.put("longUrl", entry.getLongUrl());
            response.put("createdAt", entry.getCreatedAt().toString());
            response.put("expiresAt", entry.getExpiresAt() == null ? null : entry.getExpiresAt().toString());
            response.put("clickCount", entry.getClickCount());
            ShortenHandler.sendJson(exchange, 200, JsonUtil.toJson(response));
        } catch (UrlShortenerService.UrlNotFoundException e) {
            ShortenHandler.sendError(exchange, 404, e.getMessage());
        } catch (UrlShortenerService.UrlExpiredException e) {
            ShortenHandler.sendError(exchange, 410, e.getMessage());
        }
    }
}
