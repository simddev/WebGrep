package com.webgrep.core;

import java.util.*;

/**
 * Tracks which URLs have been queued and decides whether a new URL is a duplicate.
 *
 * <h2>Smart deduplication (default, {@code allUrls = false})</h2>
 * <p>Two URLs are the same page when they differ only in <em>noise</em> query parameters -
 * parameters that never change what a page contains:
 * <ul>
 *   <li>campaign and click tracking: {@code utm_*}, {@code gclid}, {@code fbclid},
 *       {@code msclkid} and similar;</li>
 *   <li>session identifiers: {@code jsessionid}, {@code phpsessid}, {@code sessionid},
 *       {@code session_id}, and {@code sid} when its value is a long token;</li>
 *   <li>sort order: {@code sort}, {@code sortby}, {@code orderby} and their underscore forms,
 *       and {@code order}, {@code dir} or {@code direction} when the value is a direction
 *       such as {@code asc} or {@code desc}.</li>
 * </ul>
 * Every other parameter is assumed to select content, so it keeps URLs apart. The order of
 * parameters does not matter.
 *
 * <pre>
 *   queue  /list?q=x                 → key /list?q=x
 *   queue  /list?q=x&amp;sort=date       → key /list?q=x          → DUPLICATE (only a sort order added)
 *   queue  /list?q=x&amp;page=2          → key /list?page=2&amp;q=x   → not a duplicate (another page of results)
 *   queue  /item?id=5&amp;utm_source=a   → key /item?id=5         → not a duplicate of /list, queued
 * </pre>
 *
 * <p>Assuming the opposite - that any added parameter is a harmless variant - silently skips
 * real content: page 2 of a search, or later pages of a forum thread.
 *
 * <h2>All-URLs mode ({@code allUrls = true})</h2>
 * <p>Only exact-URL duplicates are suppressed; every distinct URL string is visited.
 *
 * <h2>Scheme normalisation</h2>
 * <p>{@code http://} and {@code https://} variants of the same URL are always treated as
 * duplicates. The first-encountered scheme is the one that gets fetched.
 */
public class UrlDeduplicator {

    /** Parameters that never change what a page contains, whatever their value. */
    private static final Set<String> NOISE_PARAMS = Set.of(
            // click and campaign tracking (utm_* is matched by prefix)
            "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "fbclid", "msclkid", "yclid",
            "twclid", "ttclid", "igshid", "mc_cid", "mc_eid", "_ga", "_gl", "_hsenc", "_hsmi",
            "mkt_tok", "ref_src",
            // session identifiers
            "jsessionid", "phpsessid", "sessionid", "session_id",
            // sort keys
            "sort", "sortby", "sort_by", "orderby", "order_by");

    /**
     * Parameters that are noise only when their value is a sort direction: {@code ?dir=asc}
     * sorts a listing, but {@code ?dir=/reports} opens a different folder.
     */
    private static final Set<String> DIRECTION_PARAMS = Set.of("order", "dir", "direction");

    /** Values that mark a sort direction. */
    private static final Set<String> DIRECTION_VALUES = Set.of("asc", "desc", "ascending", "descending");

    /** Shortest {@code sid} value treated as a session token rather than, say, a survey id. */
    private static final int MIN_SESSION_TOKEN_LENGTH = 16;

    /** Whether to bypass smart dedup and only suppress exact-URL duplicates. */
    private final boolean allUrls;

    /** Keys of every URL queued so far: the canonical key, or the scheme key in all-URLs mode. */
    private final Set<String> queued = new HashSet<>();

    /**
     * Constructs a new deduplicator.
     *
     * @param allUrls {@code true} to disable smart dedup and only suppress exact-URL duplicates;
     *                {@code false} (default) to ignore noise parameters when comparing URLs.
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
     * Returns the key under which smart dedup compares a URL: the scheme key with noise
     * parameters removed and the remaining parameters sorted.
     *
     * @param url a normalised URL.
     * @return the canonical key.
     */
    static String canonicalKey(String url) {
        String key = schemeKey(url);
        int q = key.indexOf('?');
        if (q < 0) return key;
        List<String> kept = new ArrayList<>();
        for (String param : key.substring(q + 1).split("&")) {
            if (!param.isEmpty() && !isNoise(param)) kept.add(param);
        }
        String base = key.substring(0, q);
        if (kept.isEmpty()) return base;
        Collections.sort(kept);
        return base + "?" + String.join("&", kept);
    }

    /**
     * Returns {@code true} if a {@code name=value} query parameter is noise (see the class
     * description).
     *
     * @param param one parameter of a query string.
     * @return whether the parameter can be ignored when comparing URLs.
     */
    static boolean isNoise(String param) {
        int eq = param.indexOf('=');
        String name = (eq < 0 ? param : param.substring(0, eq)).toLowerCase(Locale.ROOT);
        String value = eq < 0 ? "" : param.substring(eq + 1);
        if (name.startsWith("utm_") || NOISE_PARAMS.contains(name)) return true;
        if (DIRECTION_PARAMS.contains(name)) return DIRECTION_VALUES.contains(value.toLowerCase(Locale.ROOT));
        if (name.equals("sid")) {
            return value.length() >= MIN_SESSION_TOKEN_LENGTH && value.chars().allMatch(Character::isLetterOrDigit);
        }
        return false;
    }

    /**
     * Returns {@code true} if this URL should be skipped because it, or a URL differing from it
     * only in noise parameters, has already been queued.
     *
     * @param url the candidate URL to test (should be normalised via {@link com.webgrep.utils.UrlUtils#normalizeUrl}).
     * @return {@code true} if the URL should be skipped; {@code false} if it should be queued.
     */
    public boolean isDuplicate(String url) {
        return queued.contains(allUrls ? schemeKey(url) : canonicalKey(url));
    }

    /**
     * Records {@code url} as queued so that future calls to {@link #isDuplicate} will recognise
     * it and its noise variants. Must be called immediately after {@link #isDuplicate} returns
     * {@code false}.
     *
     * @param url the URL that has been accepted for queuing.
     */
    public void markQueued(String url) {
        queued.add(allUrls ? schemeKey(url) : canonicalKey(url));
    }

    /**
     * Returns the total number of distinct keys queued so far.
     *
     * @return number of queued URLs.
     */
    public int size() {
        return queued.size();
    }
}
