package com.madan.urlshortener.http;

import com.madan.urlshortener.model.AnalyticsSummary;
import com.madan.urlshortener.service.UrlShortenerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /api/analytics/{code}
 * Reply: totalClicks, clicksLast24h, clicksLast7d, a per-day breakdown, and
 * the top 5 referrers -- the "analytics" leg of the assignment, sitting
 * alongside the plain click-count in /api/stats/{code}.
 */
public class AnalyticsHandler implements HttpHandler {

    private static final String PREFIX = "/api/analytics/";

    private final UrlShortenerService service;

    public AnalyticsHandler(UrlShortenerService service) {
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
            ShortenHandler.sendError(exchange, 400, "Missing short code in path: /api/analytics/{code}");
            return;
        }

        try {
            AnalyticsSummary summary = service.buildAnalytics(code);
            ShortenHandler.sendJson(exchange, 200, JsonUtil.toJson(summary.toResponseMap()));
        } catch (UrlShortenerService.UrlNotFoundException e) {
            ShortenHandler.sendError(exchange, 404, e.getMessage());
        } catch (UrlShortenerService.UrlExpiredException e) {
            ShortenHandler.sendError(exchange, 410, e.getMessage());
        }
    }
}
