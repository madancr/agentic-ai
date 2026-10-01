package com.madan.urlshortener;

import com.madan.urlshortener.http.AnalyticsHandler;
import com.madan.urlshortener.http.HealthHandler;
import com.madan.urlshortener.http.RedirectHandler;
import com.madan.urlshortener.http.ShortenHandler;
import com.madan.urlshortener.http.StatsHandler;
import com.madan.urlshortener.reliability.RateLimiter;
import com.madan.urlshortener.reliability.RequestLoggingFilter;
import com.madan.urlshortener.service.UrlShortenerService;
import com.madan.urlshortener.store.UrlRepository;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Application entry point. Starts a plain-JDK HTTP server (no framework,
 * no external dependencies) exposing:
 *
 *   POST /api/shorten            - create a short link (rate limited per IP)
 *   GET  /{code}                  - redirect to the original URL (302)
 *   GET  /api/stats/{code}        - metadata + lifetime click count
 *   GET  /api/analytics/{code}    - clicks last 24h/7d, daily breakdown, top referrers
 *   GET  /healthz                 - liveness/readiness + basic runtime metrics
 *
 * Run with:  java -cp out com.madan.urlshortener.App [port]
 * Default port is 8080, or set the PORT environment variable.
 */
public class App {

    public static void main(String[] args) throws Exception {
        Running app = start(resolvePort(args));
        String baseUrl = app.baseUrl();
        System.out.println("URL Shortener listening on " + baseUrl);
        System.out.println("  POST " + baseUrl + "/api/shorten");
        System.out.println("  GET  " + baseUrl + "/{code}");
        System.out.println("  GET  " + baseUrl + "/api/stats/{code}");
        System.out.println("  GET  " + baseUrl + "/api/analytics/{code}");
        System.out.println("  GET  " + baseUrl + "/healthz");

        // Graceful shutdown: stop accepting new connections, give in-flight
        // requests up to 5s to finish, then exit. Without this, a container
        // orchestrator's SIGTERM during a deploy would hard-kill mid-request.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down: draining in-flight requests (up to 5s)...");
            app.stop(5);
            System.out.println("Shutdown complete.");
        }, "shutdown-hook"));
    }

    /**
     * A started server plus the threads it owns, so callers other than main() (the agent demo, tests)
     * can start the service in-process and stop it cleanly. Port 0 = let the OS pick a free port.
     */
    public record Running(HttpServer server, String baseUrl, ExecutorService workers,
                          ScheduledExecutorService housekeeping) implements AutoCloseable {

        public void stop(int drainSeconds) {
            server.stop(drainSeconds);
            workers.shutdownNow();      // worker threads are non-daemon: without this the JVM would not exit
            housekeeping.shutdownNow();
        }

        @Override
        public void close() {
            stop(0);
        }
    }

    /** Wires and starts the service. Extracted from main() unchanged so it can also be started from code. */
    public static Running start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        String baseUrl = "http://localhost:" + server.getAddress().getPort(); // real port, even when port == 0

        UrlRepository repository = new UrlRepository();
        UrlShortenerService service = new UrlShortenerService(repository, baseUrl);

        // 20-request burst capacity, refilling at 10/sec sustained -- generous
        // for a legitimate user, tight enough to blunt a naive abuse script.
        // Tune via the constructor args if load testing says otherwise.
        RateLimiter shortenRateLimiter = new RateLimiter(20, 10);

        RequestLoggingFilter accessLog = new RequestLoggingFilter();

        // Every context gets the same structured-logging filter attached at
        // registration time; com.sun.net.httpserver has no single
        // "global filter" hook, so this is done once per route here rather
        // than duplicated in each handler.
        registerWithLogging(server, "/api/shorten", new ShortenHandler(service, shortenRateLimiter), accessLog);
        registerWithLogging(server, "/api/stats/", new StatsHandler(service), accessLog);
        registerWithLogging(server, "/api/analytics/", new AnalyticsHandler(service), accessLog);
        registerWithLogging(server, "/healthz", new HealthHandler(service, shortenRateLimiter), accessLog);
        registerWithLogging(server, "/", new RedirectHandler(service), accessLog); // catch-all for short codes

        ExecutorService workers = Executors.newFixedThreadPool(16);
        server.setExecutor(workers);

        // Periodically evict idle rate-limiter buckets so a long-running
        // process doesn't accumulate one entry per distinct client IP forever.
        ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-housekeeping");
            t.setDaemon(true);
            return t;
        });
        housekeeping.scheduleAtFixedRate(shortenRateLimiter::evictStale, 10, 10, TimeUnit.MINUTES);

        server.start();
        return new Running(server, baseUrl, workers, housekeeping);
    }

    private static void registerWithLogging(HttpServer server, String path,
                                             com.sun.net.httpserver.HttpHandler handler,
                                             RequestLoggingFilter filter) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(filter);
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            return Integer.parseInt(args[0]);
        }
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            return Integer.parseInt(envPort);
        }
        return 8080;
    }
}
