package com.madan.urlshortener.model;

import java.time.Instant;

/**
 * A single recorded redirect. Kept intentionally small -- this is the unit
 * that analytics queries (daily breakdown, top referrers) are built from.
 */
public final class ClickEvent {

    private final Instant timestamp;
    private final String referrer; // may be "direct" if the client sent no Referer header
    private final String userAgent;

    public ClickEvent(Instant timestamp, String referrer, String userAgent) {
        this.timestamp = timestamp;
        this.referrer = (referrer == null || referrer.isBlank()) ? "direct" : referrer;
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "unknown" : userAgent;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getReferrer() {
        return referrer;
    }

    public String getUserAgent() {
        return userAgent;
    }
}
