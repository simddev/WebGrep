package com.webgrep.core;

import java.util.*;

/**
 * Tracks which URLs have been queued and decides whether a new URL is a duplicate.
 *
 * <h2>Smart deduplication (default, {@code allUrls = false})</h2>
 * <p>A URL with query parameters is considered a duplicate of an already-queued URL if its
 * parameter set is a <em>superset</em> of the first-queued URL's parameters for that base path
 * (same keys AND same values). This rule deduplicates sort/filter/pagination variants without
 * suppressing genuinely different content:
 *
 * <pre>
 *   queue  /list?id=1           → canonical params for /list = {"id=1"}
 *   queue  /list?id=1&amp;sort=asc  → params {"id=1","sort=asc"} ⊇ {"id=1"} → DUPLICATE (skipped)
 *   queue  /list?id=2           → params {"id=2"} ⊉ {"id=1"}               → not a duplicate (queued)
 * </pre>
 *
 * <h2>All-URLs mode ({@code allUrls = true})</h2>
 * <p>Only exact-URL duplicates are suppressed; every distinct URL string is visited regardless
 * of query parameter relationships.
 *
 * <h2>Scheme normalisation</h2>
 * <p>{@code http://} and {@code https://} variants of the same URL are always treated as
 * duplicates. The first-encountered scheme is the one that gets fetched.
 */
public class UrlDeduplicator {

    /** Whether to bypass smart dedup and only suppress exact-URL duplicates. */
    private final boolean allUrls;

    /**
     * Scheme-normalised URL strings of every URL that has been queued so far.
     * {@code http://} is stored as {@code https://} so scheme variants collapse to one entry.
     */
    private final Set<String> queued = new HashSet<>();

    /**
     * Maps each base path (scheme-normalised, no query string) to the canonical query parameter
     * set recorded when that path was first queued. An empty set means the path was queued
     * without any query parameters.
     */
    private final Map<String, Set<String>> canonicalPathParams = new HashMap<>();

    /**
     * Constructs a new deduplicator.
     *
     * @param allUrls {@code true} to disable smart dedup and only suppress exact-URL duplicates;
     *                {@code false} (default) to enable the superset-parameter dedup rule.
     */
    public UrlDeduplicator(boolean allUrls) {
        this.allUrls = allUrls;
    }

    /**
     * Returns a scheme-normalised key for the URL by mapping {@code http://} to {@code https://}.
     * This ensures that the two scheme variants of the same URL are stored under the same key
     * and treated as duplicates.
     *
     * @param url the URL to normalise.
     * @return the URL with {@code http://} replaced by {@code https://}, or the original string
     *         if the scheme is already {@code https://} or something else.
     */
    private static String schemeKey(String url) {
        return url.startsWith("http://") ? "https://" + url.substring(7) : url;
    }

    /**
     * Returns {@code true} if this URL should be skipped because it is already queued or is
     * determined to be a duplicate under the active dedup rule.
     *
     * <p>Exact duplicates (same scheme-normalised URL string) are always suppressed.
     * When {@code allUrls = false}, the superset-parameter rule is also applied.
     *
     * @param url the candidate URL to test (should be normalised via {@link com.webgrep.utils.UrlUtils#normalizeUrl}).
     * @return {@code true} if the URL should be skipped; {@code false} if it should be queued.
     */
    public boolean isDuplicate(String url) {
        String key = schemeKey(url);
        if (queued.contains(key)) return true;
        if (allUrls) return false;

        int q = key.indexOf('?');
        if (q == -1) {
            // Bare path: duplicate only if it was previously queued without any params.
            // A bare path is NOT a duplicate of a parameterised variant already queued —
            // /article after queuing /article?id=5 may well be different content.
            Set<String> canonical = canonicalPathParams.get(key);
            return canonical != null && canonical.isEmpty();
        }

        String base = key.substring(0, q);
        if (!canonicalPathParams.containsKey(base)) return false;

        Set<String> canonical = canonicalPathParams.get(base);
        // Base path was visited without any params → allow parameterised variants through;
        // the bare path itself won't be re-queued (already in the queued set above).
        if (canonical.isEmpty()) return false;

        // Parse new URL's params as key=value tokens
        Set<String> newParams = new HashSet<>(Arrays.asList(key.substring(q + 1).split("&")));
        // Duplicate if new URL contains ALL canonical key=value pairs.
        // e.g. ?id=1&sort=asc → dup of ?id=1  (adds sort param on top of same content ID)
        //      ?id=2           → NOT dup of ?id=1  (different content ID value)
        return newParams.containsAll(canonical);
    }

    /**
     * Records {@code url} as queued so that future calls to {@link #isDuplicate} will recognise
     * it. Must be called immediately after {@link #isDuplicate} returns {@code false}.
     *
     * <p>Also records the canonical query-parameter set for the URL's base path if this is the
     * first time the base path is seen (used by the superset dedup rule).
     *
     * @param url the URL that has been accepted for queuing.
     */
    public void markQueued(String url) {
        String key = schemeKey(url);
        queued.add(key);
        if (allUrls) return;

        int q = key.indexOf('?');
        String base = q != -1 ? key.substring(0, q) : key;
        if (!canonicalPathParams.containsKey(base)) {
            Set<String> params = q != -1
                    ? new HashSet<>(Arrays.asList(key.substring(q + 1).split("&")))
                    : Collections.emptySet();
            canonicalPathParams.put(base, params);
        }
    }

    /**
     * Returns the total number of distinct URLs that have been queued so far.
     *
     * @return number of queued URLs.
     */
    public int size() {
        return queued.size();
    }
}
