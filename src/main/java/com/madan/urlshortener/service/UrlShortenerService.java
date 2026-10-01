package com.madan.urlshortener.service;

import com.madan.urlshortener.model.AnalyticsSummary;
import com.madan.urlshortener.model.ClickEvent;
import com.madan.urlshortener.model.UrlEntry;
import com.madan.urlshortener.store.UrlRepository;
import com.madan.urlshortener.util.Base62;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Core business logic: turning long URLs into short codes and back.
 *
 * ID generation strategy: a single in-process atomic counter, Base62-encoded.
 * This is simple and collision-free by construction (each ID is issued
 * exactly once) but does NOT scale horizontally as-is — see README for how
 * a real deployment would replace this with a distributed ID generator
 * (e.g. Snowflake-style, or a range of IDs leased per app instance from a
 * central counter service / DB sequence).
 */
public class UrlShortenerService {

    /** Starting offset just so early codes aren't a single character ("0", "1", ...). */
    private static final long ID_OFFSET = 1_000_000L;

    /** Reserved paths that must never be handed out as short codes -- they'd shadow real endpoints. */
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "api", "healthz", "health", "favicon.ico", "robots.txt");

    private static final int MAX_TOP_REFERRERS = 5;

    private final UrlRepository repository;
    private final AtomicLong idCounter = new AtomicLong(ID_OFFSET);
    private final String baseUrl;
    private final Instant startedAt = Instant.now();

    public UrlShortenerService(UrlRepository repository, String baseUrl) {
        this.repository = repository;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * Shortens a long URL.
     *
     * @param longUrl     the URL to shorten (must be a valid absolute http/https URL)
     * @param customAlias optional caller-chosen short code; null/blank means auto-generate
     * @param ttlSeconds  optional time-to-live in seconds; null means the link never expires
     */
    public UrlEntry shorten(String longUrl, String customAlias, Long ttlSeconds) {
        validateUrl(longUrl);

        Instant now = Instant.now();
        Instant expiresAt = (ttlSeconds != null) ? now.plusSeconds(ttlSeconds) : null;

        if (customAlias != null && !customAlias.isBlank()) {
            String alias = customAlias.trim();
            validateAlias(alias);
            if (repository.existsByShortCode(alias)) {
                throw new AliasAlreadyExistsException(alias);
            }
            UrlEntry entry = new UrlEntry(alias, longUrl, now, expiresAt);
            repository.save(entry);
            return entry;
        }

        // No custom alias: dedupe on identical long URL so repeated
        // requests for the same link return the same short code.
        Optional<String> existingCode = repository.findExistingShortCodeForLongUrl(longUrl);
        if (existingCode.isPresent()) {
            Optional<UrlEntry> existing = repository.findByShortCode(existingCode.get());
            if (existing.isPresent() && !existing.get().isExpired()) {
                return existing.get();
            }
        }

        String shortCode = generateUniqueCode();
        UrlEntry entry = new UrlEntry(shortCode, longUrl, now, expiresAt);
        repository.save(entry);
        repository.indexLongUrl(longUrl, shortCode);
        return entry;
    }

    /** Resolves a short code, recording an anonymous click (no referrer/user-agent captured). */
    public UrlEntry resolve(String shortCode) {
        return resolve(shortCode, null, null);
    }

    /**
     * Resolves a short code back to its long URL, recording a click event
     * (used both for the total counter and for analytics aggregation).
     * Throws if the code is unknown or the link has expired.
     */
    public UrlEntry resolve(String shortCode, String referrer, String userAgent) {
        UrlEntry entry = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        if (entry.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }
        entry.recordClick(new ClickEvent(Instant.now(), referrer, userAgent));
        return entry;
    }

    /**
     * Aggregates the retained click history for a short code into daily
     * counts and top referrers. Note: because UrlEntry only retains the
     * most recent MAX_RETAINED_EVENTS clicks, an extremely hot link's
     * older history within the window may have been evicted -- totalClicks
     * itself stays exact regardless (see UrlEntry), only the *breakdown*
     * is subject to the retention window. Fine for a demo; a production
     * version would aggregate into pre-computed daily counters instead of
     * scanning raw events, so retention wouldn't need to trade off accuracy.
     */
    public AnalyticsSummary buildAnalytics(String shortCode) {
        UrlEntry entry = lookup(shortCode);
        List<ClickEvent> events = entry.recentEventsSnapshot();

        Instant now = Instant.now();
        Instant cutoff24h = now.minusSeconds(24L * 3600);
        Instant cutoff7d = now.minusSeconds(7L * 24 * 3600);

        long last24h = 0;
        long last7d = 0;
        Map<String, Long> dailyBreakdown = new LinkedHashMap<>();
        Map<String, Long> referrerCounts = new LinkedHashMap<>();

        DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

        for (ClickEvent event : events) {
            if (event.getTimestamp().isAfter(cutoff24h)) {
                last24h++;
            }
            if (event.getTimestamp().isAfter(cutoff7d)) {
                last7d++;
            }
            String day = dayFormatter.format(event.getTimestamp());
            dailyBreakdown.merge(day, 1L, Long::sum);
            referrerCounts.merge(event.getReferrer(), 1L, Long::sum);
        }

        List<Map.Entry<String, Long>> topReferrers = new ArrayList<>(referrerCounts.entrySet());
        topReferrers.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (topReferrers.size() > MAX_TOP_REFERRERS) {
            topReferrers = topReferrers.subList(0, MAX_TOP_REFERRERS);
        }

        return new AnalyticsSummary(shortCode, entry.getClickCount(), last24h, last7d, dailyBreakdown, topReferrers);
    }

    /** Like resolve(), but does not increment the click counter (used by the stats endpoint). */
    public UrlEntry lookup(String shortCode) {
        UrlEntry entry = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));
        if (entry.isExpired()) {
            throw new UrlExpiredException(shortCode);
        }
        return entry;
    }

    public String toShortUrl(String shortCode) {
        return baseUrl + "/" + shortCode;
    }

    public long getUptimeSeconds() {
        return Instant.now().getEpochSecond() - startedAt.getEpochSecond();
    }

    public int getTotalShortCodes() {
        return repository.size();
    }

    /** Sum of lifetime click counts across every stored entry -- an O(n) scan, fine at health-check frequency. */
    public long getTotalClicksAcrossAllEntries() {
        long sum = 0;
        for (UrlEntry entry : repository.allEntries()) {
            sum += entry.getClickCount();
        }
        return sum;
    }

    private String generateUniqueCode() {
        // Each counter value is issued exactly once, so the Base62 encoding
        // of it is guaranteed unique -- no collision-retry loop needed for
        // the auto-generated path (unlike a random-code approach would need).
        long id = idCounter.getAndIncrement();
        return Base62.encode(id);
    }

    private void validateUrl(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) {
            throw new InvalidUrlException("URL must not be empty");
        }
        try {
            URI uri = new URI(longUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new InvalidUrlException("URL must start with http:// or https://: " + longUrl);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new InvalidUrlException("URL must include a host: " + longUrl);
            }
        } catch (Exception e) {
            if (e instanceof InvalidUrlException) {
                throw (InvalidUrlException) e;
            }
            throw new InvalidUrlException("Malformed URL: " + longUrl);
        }
    }

    private void validateAlias(String alias) {
        if (!alias.matches("[A-Za-z0-9_-]{3,32}")) {
            throw new InvalidUrlException(
                    "Custom alias must be 3-32 characters, alphanumeric plus '-' or '_': " + alias);
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase())) {
            throw new InvalidUrlException("'" + alias + "' is reserved and cannot be used as a custom alias");
        }
    }

    // ---- Exceptions -------------------------------------------------

    public static class InvalidUrlException extends RuntimeException {
        public InvalidUrlException(String message) {
            super(message);
        }
    }

    public static class AliasAlreadyExistsException extends RuntimeException {
        public AliasAlreadyExistsException(String alias) {
            super("Alias already in use: " + alias);
        }
    }

    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String code) {
            super("No URL found for code: " + code);
        }
    }

    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String code) {
            super("URL has expired for code: " + code);
        }
    }
}
