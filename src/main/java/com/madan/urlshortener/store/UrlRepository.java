package com.madan.urlshortener.store;

import com.madan.urlshortener.model.UrlEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory storage for URL mappings.
 *
 * This is intentionally behind an interface-like contract (public methods
 * only, no leaking of the underlying map) so it can be swapped for a real
 * datastore (Redis, DynamoDB, a SQL table keyed by shortCode) without
 * touching the service layer. See README "Scaling this to production"
 * for the tradeoffs of each backing store.
 */
public class UrlRepository {

    private final ConcurrentMap<String, UrlEntry> byShortCode = new ConcurrentHashMap<>();

    // Reverse index so re-shortening the same long URL (without a custom
    // alias) returns the existing short code instead of minting a new one.
    private final ConcurrentMap<String, String> byLongUrl = new ConcurrentHashMap<>();

    public void save(UrlEntry entry) {
        byShortCode.put(entry.getShortCode(), entry);
    }

    public Optional<UrlEntry> findByShortCode(String shortCode) {
        return Optional.ofNullable(byShortCode.get(shortCode));
    }

    public boolean existsByShortCode(String shortCode) {
        return byShortCode.containsKey(shortCode);
    }

    public Optional<String> findExistingShortCodeForLongUrl(String longUrl) {
        return Optional.ofNullable(byLongUrl.get(longUrl));
    }

    public void indexLongUrl(String longUrl, String shortCode) {
        byLongUrl.putIfAbsent(longUrl, shortCode);
    }

    public int size() {
        return byShortCode.size();
    }

    /** Snapshot of all stored entries, used for aggregate health/metrics reporting. */
    public Collection<UrlEntry> allEntries() {
        return new ArrayList<>(byShortCode.values());
    }
}
