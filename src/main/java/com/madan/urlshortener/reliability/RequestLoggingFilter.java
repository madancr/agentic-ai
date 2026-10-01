package com.madan.urlshortener.reliability;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Structured (JSON-line) access logging, wrapped around every request.
 *
 * Plain text logs ("GET /abc123 200") are fine to read by eye but painful
 * to query once you have real volume; emitting one JSON object per request
 * to stdout means it can be shipped as-is into any log aggregator
 * (CloudWatch, Datadog, ELK, Splunk) and filtered/aggregated by field
 * (status code, path, latency) without a custom parser.
 *
 * Each request also gets a random request ID, echoed back as a response
 * header (X-Request-Id) and included in the log line and in error bodies --
 * this is what lets you go from "a user says redirect X failed" to the
 * exact log line for that request, which matters a lot more once there's
 * more than one instance running.
 */
public class RequestLoggingFilter extends Filter {

    @Override
    public String description() {
        return "Structured request/response access logging";
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        long startNanos = System.nanoTime();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        exchange.setAttribute("requestId", requestId);

        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String remote = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";

        IOException thrown = null;
        try {
            chain.doFilter(exchange);
        } catch (IOException e) {
            thrown = e;
            throw e;
        } finally {
            long durationMicros = (System.nanoTime() - startNanos) / 1_000;
            int status = exchange.getResponseCode(); // -1 if the handler never sent a response
            String line = String.format(
                    "{\"ts\":\"%s\",\"requestId\":\"%s\",\"method\":\"%s\",\"path\":\"%s\","
                            + "\"status\":%d,\"durationMicros\":%d,\"remoteAddr\":\"%s\"%s}",
                    Instant.now(), requestId, method, escapeForLog(path), status, durationMicros, remote,
                    thrown != null ? ",\"error\":\"" + escapeForLog(thrown.getMessage()) + "\"" : ""
            );
            System.out.println(line);
        }
    }

    private static String escapeForLog(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
