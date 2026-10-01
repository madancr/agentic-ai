package com.madan.urlshortener;

import com.madan.urlshortener.model.AnalyticsSummary;
import com.madan.urlshortener.model.UrlEntry;
import com.madan.urlshortener.reliability.RateLimiter;
import com.madan.urlshortener.service.UrlShortenerService;
import com.madan.urlshortener.store.UrlRepository;
import com.madan.urlshortener.util.Base62;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A tiny, dependency-free test runner (no JUnit needed, so this project
 * compiles and runs with nothing but the JDK). Each test* method throws an
 * AssertionError on failure; run() reports a pass/fail summary.
 *
 * Run with:  java -cp out com.madan.urlshortener.AllTests
 */
public class AllTests {

    private int passed = 0;
    private int failed = 0;

    public static void main(String[] args) {
        AllTests runner = new AllTests();
        runner.run();
        if (runner.failed > 0) {
            System.exit(1);
        }
    }

    private void run() {
        test("base62 round trip", this::testBase62RoundTrip);
        test("base62 is deterministic and unique per counter value", this::testBase62Uniqueness);
        test("shorten then resolve returns original URL", this::testShortenAndResolve);
        test("resolving increments click count", this::testClickCountIncrements);
        test("re-shortening same URL returns same code", this::testDedupe);
        test("custom alias is honored", this::testCustomAlias);
        test("duplicate custom alias is rejected", this::testDuplicateAliasRejected);
        test("invalid URL is rejected", this::testInvalidUrlRejected);
        test("unknown short code raises not-found", this::testUnknownCodeNotFound);
        test("expired link raises expired error", this::testExpiredLink);
        test("reserved alias is rejected", this::testReservedAliasRejected);
        test("analytics: daily breakdown and top referrers", this::testAnalyticsAggregation);
        test("analytics: clicksLast24h/7d reflect recent activity", this::testAnalyticsRecentWindows);
        test("health metrics: total codes and lifetime clicks", this::testHealthMetrics);
        test("rate limiter: allows burst then throttles", this::testRateLimiterThrottles);
        test("rate limiter: refills tokens over time", this::testRateLimiterRefills);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
    }

    private void test(String name, Runnable body) {
        try {
            body.run();
            System.out.println("PASS - " + name);
            passed++;
        } catch (Throwable t) {
            System.out.println("FAIL - " + name + " :: " + t);
            failed++;
        }
    }

    // ---- individual tests --------------------------------------------

    private void testBase62RoundTrip() {
        long[] samples = {0, 1, 61, 62, 63, 12345, 1_000_000, Long.MAX_VALUE};
        for (long v : samples) {
            String encoded = Base62.encode(v);
            long decoded = Base62.decode(encoded);
            assertEquals(v, decoded, "round trip for " + v);
        }
    }

    private void testBase62Uniqueness() {
        Set<String> seen = new HashSet<>();
        for (long i = 0; i < 5000; i++) {
            String code = Base62.encode(i);
            assertTrue(seen.add(code), "duplicate code generated for i=" + i);
        }
    }

    private void testShortenAndResolve() {
        UrlShortenerService service = newService();
        UrlEntry entry = service.shorten("https://example.com/some/long/path", null, null);
        UrlEntry resolved = service.resolve(entry.getShortCode());
        assertEquals("https://example.com/some/long/path", resolved.getLongUrl(), "resolved URL");
    }

    private void testClickCountIncrements() {
        UrlShortenerService service = newService();
        UrlEntry entry = service.shorten("https://example.com/x", null, null);
        assertEquals(0L, entry.getClickCount(), "initial click count");
        service.resolve(entry.getShortCode());
        service.resolve(entry.getShortCode());
        UrlEntry after = service.lookup(entry.getShortCode());
        assertEquals(2L, after.getClickCount(), "click count after two resolves");
    }

    private void testDedupe() {
        UrlShortenerService service = newService();
        UrlEntry first = service.shorten("https://example.com/same", null, null);
        UrlEntry second = service.shorten("https://example.com/same", null, null);
        assertEquals(first.getShortCode(), second.getShortCode(), "dedup short code");
    }

    private void testCustomAlias() {
        UrlShortenerService service = newService();
        UrlEntry entry = service.shorten("https://example.com/resume", "madan-resume", null);
        assertEquals("madan-resume", entry.getShortCode(), "custom alias used verbatim");
    }

    private void testDuplicateAliasRejected() {
        UrlShortenerService service = newService();
        service.shorten("https://example.com/a", "taken", null);
        try {
            service.shorten("https://example.com/b", "taken", null);
            throw new AssertionError("expected AliasAlreadyExistsException");
        } catch (UrlShortenerService.AliasAlreadyExistsException expected) {
            // pass
        }
    }

    private void testInvalidUrlRejected() {
        UrlShortenerService service = newService();
        try {
            service.shorten("not-a-url", null, null);
            throw new AssertionError("expected InvalidUrlException");
        } catch (UrlShortenerService.InvalidUrlException expected) {
            // pass
        }
    }

    private void testUnknownCodeNotFound() {
        UrlShortenerService service = newService();
        try {
            service.resolve("doesNotExist");
            throw new AssertionError("expected UrlNotFoundException");
        } catch (UrlShortenerService.UrlNotFoundException expected) {
            // pass
        }
    }

    private void testExpiredLink() {
        UrlShortenerService service = newService();
        // ttlSeconds = 0 means it expires immediately (createdAt == expiresAt, "now" moves past it).
        UrlEntry entry = service.shorten("https://example.com/temp", null, 0L);
        try {
            Thread.sleep(5); // ensure Instant.now() has moved past expiresAt
        } catch (InterruptedException ignored) {
        }
        try {
            service.resolve(entry.getShortCode());
            throw new AssertionError("expected UrlExpiredException");
        } catch (UrlShortenerService.UrlExpiredException expected) {
            // pass
        }
    }

    private void testReservedAliasRejected() {
        UrlShortenerService service = newService();
        try {
            service.shorten("https://example.com/x", "healthz", null);
            throw new AssertionError("expected InvalidUrlException for reserved alias");
        } catch (UrlShortenerService.InvalidUrlException expected) {
            // pass
        }
    }

    private void testAnalyticsAggregation() {
        UrlShortenerService service = newService();
        UrlEntry entry = service.shorten("https://example.com/popular", null, null);

        service.resolve(entry.getShortCode(), "https://twitter.com/x", "curl/8.0");
        service.resolve(entry.getShortCode(), "https://twitter.com/x", "curl/8.0");
        service.resolve(entry.getShortCode(), "https://news.ycombinator.com", "curl/8.0");
        service.resolve(entry.getShortCode(), null, "curl/8.0"); // -> "direct"

        AnalyticsSummary summary = service.buildAnalytics(entry.getShortCode());
        assertEquals(4L, summary.getTotalClicks(), "total clicks");

        Map<String, Long> daily = summary.getDailyBreakdown();
        long dailyTotal = daily.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(4L, dailyTotal, "daily breakdown should sum to total clicks");

        assertTrue(!summary.getTopReferrers().isEmpty(), "top referrers should not be empty");
        Map.Entry<String, Long> topReferrer = summary.getTopReferrers().get(0);
        assertEquals("https://twitter.com/x", topReferrer.getKey(), "most frequent referrer");
        assertEquals(2L, topReferrer.getValue(), "most frequent referrer count");
    }

    private void testAnalyticsRecentWindows() {
        UrlShortenerService service = newService();
        UrlEntry entry = service.shorten("https://example.com/recent", null, null);
        service.resolve(entry.getShortCode());
        service.resolve(entry.getShortCode());

        AnalyticsSummary summary = service.buildAnalytics(entry.getShortCode());
        assertEquals(2L, summary.getClicksLast24h(), "clicks in last 24h");
        assertEquals(2L, summary.getClicksLast7d(), "clicks in last 7d");
    }

    private void testHealthMetrics() {
        UrlRepository repository = new UrlRepository();
        UrlShortenerService service = new UrlShortenerService(repository, "http://localhost:8080");

        UrlEntry a = service.shorten("https://example.com/a", null, null);
        UrlEntry b = service.shorten("https://example.com/b", null, null);
        service.resolve(a.getShortCode());
        service.resolve(a.getShortCode());
        service.resolve(b.getShortCode());

        assertEquals(2, service.getTotalShortCodes(), "total short codes");
        assertEquals(3L, service.getTotalClicksAcrossAllEntries(), "total lifetime clicks across all entries");
        assertTrue(service.getUptimeSeconds() >= 0, "uptime should be non-negative");
    }

    private void testRateLimiterThrottles() {
        RateLimiter limiter = new RateLimiter(3, 1); // capacity 3, refill 1/sec -- slow refill so the burst matters
        String client = "1.2.3.4";
        assertTrue(limiter.tryAcquire(client), "1st request within burst capacity");
        assertTrue(limiter.tryAcquire(client), "2nd request within burst capacity");
        assertTrue(limiter.tryAcquire(client), "3rd request within burst capacity");
        assertTrue(!limiter.tryAcquire(client), "4th request should exceed burst capacity");
    }

    private void testRateLimiterRefills() {
        RateLimiter limiter = new RateLimiter(1, 20); // capacity 1, refills fast (20/sec = one every 50ms)
        String client = "5.6.7.8";
        assertTrue(limiter.tryAcquire(client), "first request consumes the only token");
        assertTrue(!limiter.tryAcquire(client), "immediate second request should be throttled");
        try {
            Thread.sleep(150); // >> 50ms refill interval, so a token should be available again
        } catch (InterruptedException ignored) {
        }
        assertTrue(limiter.tryAcquire(client), "request after waiting should succeed once refilled");
    }

    // ---- helpers -------------------------------------------------------

    private UrlShortenerService newService() {
        return new UrlShortenerService(new UrlRepository(), "http://localhost:8080");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " -- expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
