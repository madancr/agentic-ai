package com.madan.urlshortener.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single shortened-URL record.
 *
 * Immutable except for click tracking, which is updated atomically every
 * time the short link is resolved (i.e. every redirect):
 *  - totalClicks is an exact, never-trimmed lifetime counter (cheap O(1) reads).
 *  - recentEvents is a BOUNDED ring of the most recent click events, used to
 *    power analytics (daily breakdown, top referrers) without letting a
 *    single hot link grow this object's memory footprint without bound.
 */
public final class UrlEntry {

    /** Cap on retained click events per short code. See README "Bounded analytics history". */
    public static final int MAX_RETAINED_EVENTS = 5_000;

    private final String shortCode;
    private final String longUrl;
    private final Instant createdAt;
    private final Instant expiresAt; // nullable => never expires
    private final AtomicLong totalClicks = new AtomicLong(0);
    private final ConcurrentLinkedDeque<ClickEvent> recentEvents = new ConcurrentLinkedDeque<>();

    public UrlEntry(String shortCode, String longUrl, Instant createdAt, Instant expiresAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getLongUrl() {
        return longUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /** Records a click: increments the exact total and appends to the bounded recent-event ring. */
    public long recordClick(ClickEvent event) {
        recentEvents.addLast(event);
        while (recentEvents.size() > MAX_RETAINED_EVENTS) {
            recentEvents.pollFirst(); // drop oldest; totalClicks below stays exact regardless
        }
        return totalClicks.incrementAndGet();
    }

    public long getClickCount() {
        return totalClicks.get();
    }

    /** Snapshot of retained recent events, oldest first. Safe to iterate without external locking. */
    public List<ClickEvent> recentEventsSnapshot() {
        return new ArrayList<>(recentEvents);
    }
}
