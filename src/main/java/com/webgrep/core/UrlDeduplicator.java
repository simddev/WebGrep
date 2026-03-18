package com.webgrep.core;

import java.util.*;

/**
 * Tracks which URLs have been queued and decides whether a new URL is a duplicate.
 *
 * Smart dedup rule (when allUrls=false):
 *   A URL is a duplicate if its query params are a superset of the first-queued URL's
 *   params for that base path (same keys AND same values). This deduplicates sort/filter
 *   variants (e.g. ?id=1&sort=asc vs ?id=1) without collapsing content-addressed URLs
 *   (e.g. ?souborid=9477999 vs ?souborid=1234).
 *
 * When allUrls=true, only exact-URL duplicates are suppressed.
 */
public class UrlDeduplicator {

    private final boolean allUrls;
    // Exact URL strings queued so far
    private final Set<String> queued = new HashSet<>();
    // base path (no query) → canonical key=value pairs from first queuing
    private final Map<String, Set<String>> canonicalPathParams = new HashMap<>();

    public UrlDeduplicator(boolean allUrls) {
        this.allUrls = allUrls;
    }

    /** Returns true if this URL should be skipped (already queued or a known duplicate). */
    public boolean isDuplicate(String url) {
        if (queued.contains(url)) return true;
        if (allUrls) return false;

        int q = url.indexOf('?');
        if (q == -1) {
            // No query params: duplicate if we've seen this base path before
            return canonicalPathParams.containsKey(url);
        }

        String base = url.substring(0, q);
        if (!canonicalPathParams.containsKey(base)) return false;

        Set<String> canonical = canonicalPathParams.get(base);
        // Base path was visited without any params → any parameterized variant is a dup
        if (canonical.isEmpty()) return true;

        // Parse new URL's params as key=value tokens
        Set<String> newParams = new HashSet<>(Arrays.asList(url.substring(q + 1).split("&")));
        // Duplicate if new URL contains ALL canonical key=value pairs
        // e.g. ?subjkod=207020&r=agenda → dup of ?subjkod=207020  (adds param on top)
        //      ?souborid=1234           → NOT dup of ?souborid=9477999  (different value)
        return newParams.containsAll(canonical);
    }

    /** Records this URL as queued. Must be called after isDuplicate() returns false. */
    public void markQueued(String url) {
        queued.add(url);
        if (allUrls) return;

        int q = url.indexOf('?');
        String base = q != -1 ? url.substring(0, q) : url;
        if (!canonicalPathParams.containsKey(base)) {
            Set<String> params = q != -1
                    ? new HashSet<>(Arrays.asList(url.substring(q + 1).split("&")))
                    : Collections.emptySet();
            canonicalPathParams.put(base, params);
        }
    }

    /** Total number of distinct URLs queued so far. */
    public int size() {
        return queued.size();
    }
}
