package com.madan.urlshortener.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated view of a short code's traffic, computed on demand from its
 * retained click events. Plain data holder -- the aggregation logic lives
 * in UrlShortenerService.buildAnalytics(), this just carries the result.
 */
public final class AnalyticsSummary {

    private final String shortCode;
    private final long totalClicks;
    private final long clicksLast24h;
    private final long clicksLast7d;
    private final Map<String, Long> dailyBreakdown; // "2026-09-27" -> count, oldest first
    private final List<Map.Entry<String, Long>> topReferrers; // referrer -> count, highest first

    public AnalyticsSummary(String shortCode, long totalClicks, long clicksLast24h, long clicksLast7d,
                             Map<String, Long> dailyBreakdown, List<Map.Entry<String, Long>> topReferrers) {
        this.shortCode = shortCode;
        this.totalClicks = totalClicks;
        this.clicksLast24h = clicksLast24h;
        this.clicksLast7d = clicksLast7d;
        this.dailyBreakdown = dailyBreakdown;
        this.topReferrers = topReferrers;
    }

    public String getShortCode() {
        return shortCode;
    }

    public long getTotalClicks() {
        return totalClicks;
    }

    public long getClicksLast24h() {
        return clicksLast24h;
    }

    public long getClicksLast7d() {
        return clicksLast7d;
    }

    public Map<String, Long> getDailyBreakdown() {
        return dailyBreakdown;
    }

    public List<Map.Entry<String, Long>> getTopReferrers() {
        return topReferrers;
    }

    /** Convenience for handlers: turns this into the nested Map/List shape JsonUtil.toJson expects. */
    public Map<String, Object> toResponseMap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shortCode", shortCode);
        response.put("totalClicks", totalClicks);
        response.put("clicksLast24h", clicksLast24h);
        response.put("clicksLast7d", clicksLast7d);
        response.put("dailyBreakdown", dailyBreakdown);

        List<Map<String, Object>> referrers = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> e : topReferrers) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("referrer", e.getKey());
            r.put("clicks", e.getValue());
            referrers.add(r);
        }
        response.put("topReferrers", referrers);
        return response;
    }
}
