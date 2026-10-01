package com.madan.urlshortener.reliability;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A simple per-key token-bucket rate limiter.
 *
 * Chosen over a fixed-window counter because it doesn't have the
 * "double burst at the window boundary" problem (a client hitting the last
 * millisecond of one window and the first millisecond of the next can send
 * 2x the intended limit with fixed windows). Token bucket smooths that out
 * by refilling continuously rather than resetting all at once.
 *
 * This is in-process/in-memory, which is the right call for a single
 * instance but NOT sufficient once you run multiple instances behind a load
 * balancer -- see README "Scaling rate limiting" for how that changes
 * (shared store like Redis with an atomic INCR+EXPIRE, or a Lua script for
 * an atomic token-bucket check).
 */
public class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;

        Bucket(double tokens, long lastRefillNanos) {
            this.tokens = tokens;
            this.lastRefillNanos = lastRefillNanos;
        }
    }

    private final double capacity;
    private final double refillTokensPerSecond;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(double capacity, double refillTokensPerSecond) {
        this.capacity = capacity;
        this.refillTokensPerSecond = refillTokensPerSecond;
    }

    /** Returns true if the request for this key is allowed (and consumes one token). */
    public synchronized boolean tryAcquire(String key) {
        long now = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));

        double elapsedSeconds = (now - bucket.lastRefillNanos) / 1_000_000_000.0;
        bucket.tokens = Math.min(capacity, bucket.tokens + elapsedSeconds * refillTokensPerSecond);
        bucket.lastRefillNanos = now;

        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Rough estimate of seconds until this key will have a token available, for a Retry-After header. */
    public int estimateRetryAfterSeconds(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null || bucket.tokens >= 1.0) {
            return 1;
        }
        double needed = 1.0 - bucket.tokens;
        return Math.max(1, (int) Math.ceil(needed / refillTokensPerSecond));
    }

    /** Evicts buckets that have been idle long enough to be back at full capacity, to bound memory. */
    public void evictStale() {
        long now = System.nanoTime();
        buckets.entrySet().removeIf(entry -> {
            long idleSeconds = (now - entry.getValue().lastRefillNanos) / 1_000_000_000L;
            return idleSeconds > 3600; // idle an hour -> definitely refilled, safe to drop
        });
    }

    public int trackedKeyCount() {
        return buckets.size();
    }
}
