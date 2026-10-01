package com.madan.urlshortener.http;

import com.madan.urlshortener.model.UrlEntry;
import com.madan.urlshortener.service.UrlShortenerService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /{code}  -> 302 redirect to the original long URL, and bumps the click counter.
 *
 * Registered at the server root ("/"), so any path with no further slashes
 * is treated as a short code lookup. "/" itself returns a small landing message.
 */
public class RedirectHandler implements HttpHandler {

    private final UrlShortenerService service;

    public RedirectHandler(UrlShortenerService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            ShortenHandler.sendError(exchange, 405, "Method Not Allowed. Use GET.");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        String code = path.startsWith("/") ? path.substring(1) : path;

        if (code.isEmpty()) {
            ShortenHandler.sendJson(exchange, 200,
                    "{\"service\":\"url-shortener\",\"status\":\"ok\"}");
            return;
        }

        try {
            String referrer = exchange.getRequestHeaders().getFirst("Referer");
            String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            UrlEntry entry = service.resolve(code, referrer, userAgent);
            exchange.getResponseHeaders().set("Location", entry.getLongUrl());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        } catch (UrlShortenerService.UrlNotFoundException e) {
            ShortenHandler.sendError(exchange, 404, e.getMessage());
        } catch (UrlShortenerService.UrlExpiredException e) {
            ShortenHandler.sendError(exchange, 410, e.getMessage());
        }
    }
}
