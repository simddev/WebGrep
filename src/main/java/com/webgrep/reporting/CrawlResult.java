package com.webgrep.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates the results and statistics produced by a single web crawl run.
 *
 * <p>Mutable by design - the {@link com.webgrep.core.Crawler} fills in fields as pages are
 * visited, and {@link ReportWriter} reads them when the crawl is complete. Fields are public
 * for direct access to keep the data-transfer style simple.
 *
 * <p>Error counts are pre-populated to zero for every {@link ErrorType} on construction so
 * that callers can always safely call {@link #incrementError(ErrorType)} without null checks.
 *
 * <p>{@code LinkedHashMap} is used throughout to preserve insertion order, so results appear
 * in the order pages were actually visited (BFS/DFS traversal order).
 */
public class CrawlResult {

    /**
     * Categories of non-fatal errors that can occur during a crawl.
     * Each category is counted separately and reported in the summary.
     */
    public enum ErrorType {
        /** Request failed due to a network-level problem: DNS failure, timeout, connection refused,
         *  SSL error, or an unexpected HTTP status code (not 403/429). */
        NETWORK_ERROR,
        /** Page returned HTTP 403 or 429, or a bot-protection challenge page was detected. */
        BLOCKED,
        /** Jsoup failed to parse the HTML response body. */
        PARSE_ERROR,
        /** Response body exceeded the {@code --max-bytes} limit and was not parsed. */
        SKIPPED_SIZE,
        /** Reserved for future content-type filtering. Currently unused. */
        SKIPPED_TYPE
    }

    /** URL → total keyword match count, in crawl order. Only URLs with at least one match appear. */
    public final Map<String, Integer> results = new LinkedHashMap<>();

    /** URL → list of context snippets (up to 3 per page), in crawl order. */
    public final Map<String, List<String>> snippets = new LinkedHashMap<>();

    /** URL → human-readable reason, for every page that returned 403/429 or a bot challenge. */
    public final Map<String, String> blockedUrls = new LinkedHashMap<>();

    /** Per-category error counts. All keys are pre-populated to 0 in the constructor. */
    public final Map<ErrorType, Integer> errorCounts = new LinkedHashMap<>();

    /** Breakdown of network errors by cause, e.g. {@code "Timeout" → 5}, {@code "DNS failure" → 1}. */
    public final Map<String, Integer> networkErrorReasons = new LinkedHashMap<>();

    /** Total number of URLs dequeued and fetched, regardless of content type or outcome. */
    public int visitedCount = 0;

    /** Number of pages whose content was successfully extracted (HTML + binary documents). */
    public int parsedCount = 0;

    /** Number of binary documents (PDF, DOCX, etc.) parsed by Apache Tika. Always {@code <= parsedCount}. */
    public int docsCount = 0;

    /** Wall-clock duration of the entire crawl in milliseconds, set by {@link com.webgrep.Main}
     *  after {@link com.webgrep.core.Crawler#crawl()} returns. */
    public long durationMs = 0;

    /** {@code 0} if the crawl ran to natural completion; otherwise the {@code --max-hits} value
     *  that triggered an early stop. */
    public int stoppedAtMaxHits = 0;

    /** Running total of all keyword occurrences across all visited pages. Incremented by {@link #addMatch}. */
    private int totalMatches = 0;

    /**
     * Constructs a new, empty {@code CrawlResult} and pre-populates all {@link ErrorType} counters
     * with zero so callers never need null-checks before calling {@link #incrementError}.
     */
    public CrawlResult() {
        for (ErrorType type : ErrorType.values()) {
            errorCounts.put(type, 0);
        }
    }

    /**
     * Returns the running total of keyword occurrences found across all visited pages so far.
     *
     * @return cumulative match count; {@code 0} if no matches have been found yet.
     */
    public int getTotalMatches() {
        return totalMatches;
    }

    /**
     * Records that {@code count} keyword occurrences were found on the page at {@code url}.
     * If the URL was already added (should not happen in practice), the counts are summed.
     *
     * @param url   the canonical URL of the page where matches were found.
     * @param count the number of keyword occurrences on that page; must be {@code >= 1}.
     */
    public void addMatch(String url, int count) {
        results.merge(url, count, Integer::sum);
        totalMatches += count;
    }

    /**
     * Attaches context snippets to a URL that already has matches recorded via {@link #addMatch}.
     * Snippets for the same URL are accumulated across multiple calls (though in practice each
     * URL is only visited once).
     *
     * @param url          the canonical URL the snippets belong to.
     * @param snippetList  context strings produced by {@link com.webgrep.core.MatchEngine#findSnippets};
     *                     ignored if empty.
     */
    public void addSnippets(String url, List<String> snippetList) {
        if (!snippetList.isEmpty()) {
            snippets.computeIfAbsent(url, k -> new ArrayList<>()).addAll(snippetList);
        }
    }

    /**
     * Records that {@code url} was inaccessible due to a bot-protection or rate-limit response,
     * and increments the {@link ErrorType#BLOCKED} counter.
     *
     * @param url    the URL that was blocked.
     * @param reason a short human-readable description, e.g. {@code "HTTP 403 (Access Denied)"}.
     */
    public void addBlocked(String url, String reason) {
        blockedUrls.put(url, reason);
        incrementError(ErrorType.BLOCKED);
    }

    /**
     * Increments the counter for the given error category by one.
     *
     * @param type the category of error that occurred.
     */
    public void incrementError(ErrorType type) {
        errorCounts.put(type, errorCounts.get(type) + 1);
    }

    /**
     * Records a network-level failure, incrementing both the {@link ErrorType#NETWORK_ERROR}
     * count and the per-cause breakdown in {@link #networkErrorReasons}.
     * The cause is classified by exception type via {@link #classifyException}.
     *
     * @param e the exception that caused the failure.
     */
    public void addNetworkError(Exception e) {
        incrementError(ErrorType.NETWORK_ERROR);
        networkErrorReasons.merge(classifyException(e), 1, Integer::sum);
    }

    /**
     * Records a network-level failure with an explicit reason string, incrementing both the
     * {@link ErrorType#NETWORK_ERROR} count and the per-cause breakdown.
     *
     * @param reason a short description of the failure, e.g. {@code "HTTP 404"}.
     */
    public void addNetworkError(String reason) {
        incrementError(ErrorType.NETWORK_ERROR);
        networkErrorReasons.merge(reason, 1, Integer::sum);
    }

    /**
     * Maps an exception type to a short, human-readable cause string for use in
     * {@link #networkErrorReasons}. Known exception types produce concise labels;
     * unknown types fall back to the exception message or class name.
     *
     * @param e the exception to classify.
     * @return a short cause label.
     */
    private String classifyException(Exception e) {
        return switch (e.getClass().getSimpleName()) {
            case "SocketTimeoutException"               -> "Timeout";
            case "UnknownHostException"                 -> "DNS failure";
            case "ConnectException"                     -> "Connection refused";
            case "SSLException", "SSLHandshakeException",
                 "SSLPeerUnverifiedException"          -> "SSL error";
            case "UnsupportedMimeTypeException"         -> "Unsupported content type";
            default -> {
                String msg = e.getMessage();
                yield msg != null ? msg.split("\n")[0] : e.getClass().getSimpleName();
            }
        };
    }
}
