package com.madan.urlshortener.http;

import com.madan.urlshortener.reliability.RateLimiter;
import com.madan.urlshortener.service.UrlShortenerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /healthz
 *
 * A liveness/readiness endpoint for whatever's in front of this service
 * (a load balancer, Kubernetes probe, uptime monitor) to poll. Deliberately
 * cheap to compute and always 200 as long as the process is up and able to
 * read its own repository -- it does not depend on any external system, so
 * it can't report "unhealthy" just because a downstream dependency hiccuped
 * (there are none here, but that's the principle to carry into a version
 * that does have a database/cache behind it: a liveness check should only
 * fail if *this* process is broken, not if something it calls is slow).
 */
public class HealthHandler implements HttpHandler {

    private final UrlShortenerService service;
    private final RateLimiter shortenRateLimiter;

    public HealthHandler(UrlShortenerService service, RateLimiter shortenRateLimiter) {
        this.service = service;
        this.shortenRateLimiter = shortenRateLimiter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ShortenHandler.sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("uptimeSeconds", service.getUptimeSeconds());
        body.put("totalShortCodes", service.getTotalShortCodes());
        body.put("totalClicksLifetime", service.getTotalClicksAcrossAllEntries());
        body.put("rateLimiterTrackedClients", shortenRateLimiter.trackedKeyCount());

        ShortenHandler.sendJson(exchange, 200, JsonUtil.toJson(body));
    }
}
