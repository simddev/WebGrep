package com.webgrep.core;

import com.webgrep.config.CliOptions;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.utils.UrlUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.net.ssl.*;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Crawls a website starting from a seed URL and searches every visited page for a keyword.
 *
 * <p>The crawler supports both breadth-first (default) and depth-first traversal, configurable
 * politeness delays, per-request body size limits, and automatic retry with exponential backoff
 * on HTTP 429 (Too Many Requests) responses. A session cookie jar is maintained across all
 * requests so that pages that set cookies (e.g. for session management) remain accessible.
 *
 * <p>Domain scoping rules:
 * <ul>
 *   <li>If the seed URL starts with {@code www.}, all subdomains of the root domain are followed
 *       (e.g. seed {@code www.example.com} also follows {@code docs.example.com}).</li>
 *   <li>Otherwise, only the exact domain and its {@code www.} alias are followed.</li>
 *   <li>Use {@code --allow-external} to disable domain scoping entirely.</li>
 * </ul>
 *
 * <p>HTML pages are parsed with Jsoup; all other content types are passed to
 * {@link ContentExtractor} for binary extraction via Apache Tika.
 */
public class Crawler {
    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    private static final int MAX_RETRIES = 3;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private int spinnerIdx = 0;
    private final Map<String, String> cookieJar = new HashMap<>();
    private Boolean spaRenderingEnabled = null; // null = not yet asked, true/false = decided

    private final CliOptions options;
    private final ContentExtractor extractor;
    private final MatchEngine matchEngine;
    private String startDomain;
    private boolean allowSubdomains;
    private final int maxBodySize;
    private final UrlDeduplicator dedup;
    private final SSLSocketFactory insecureSslFactory;

    public Crawler(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine) {
        this.options = options;
        this.extractor = extractor;
        this.matchEngine = matchEngine;
        String startHost = extractHost(UrlUtils.normalizeUrl(options.getUrl(), null));
        boolean seedHasWww = startHost.startsWith("www.");
        this.startDomain = seedHasWww ? startHost.substring(4) : startHost;
        this.allowSubdomains = seedHasWww;
        this.maxBodySize = (int) Math.min(options.getMaxBytes(), Integer.MAX_VALUE);
        this.dedup = new UrlDeduplicator(options.isAllUrls());

        if (options.isInsecure()) {
            this.insecureSslFactory = buildInsecureSslFactory();
            // Jsoup has no per-connection hostname verifier API; this global setter is the
            // minimal remaining side effect of --insecure. It only runs when explicitly requested.
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } else {
            this.insecureSslFactory = null;
        }
    }

    private String extractHost(String url) {
        try {
            return new URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isSameDomain(String linkHost) {
        String h = linkHost.toLowerCase();
        if (allowSubdomains) {
            // Seed had www. (e.g. www.wikipedia.org) — allow all subdomains of the root domain
            // so en.wikipedia.org, de.wikipedia.org etc. are all followed.
            return h.equals(startDomain) || h.endsWith("." + startDomain);
        }
        // Seed had no www. — only allow the exact domain and its www. alias.
        return h.equals(startDomain) || h.equals("www." + startDomain);
    }

    public CrawlResult crawl() {
        CrawlResult crawlResult = new CrawlResult();
        Deque<UrlDepth> queue = new LinkedList<>();
        PlaywrightRenderer renderer = null;

        String normalizedStart = UrlUtils.normalizeUrl(options.getUrl(), null);
        queue.addLast(new UrlDepth(normalizedStart, 0));
        dedup.markQueued(normalizedStart);

        try {
            while (!queue.isEmpty() && crawlResult.visitedCount < options.getMaxPages()) {
                UrlDepth current = queue.pollFirst();

                try {
                    Thread.sleep(options.getDelayMs());

                    org.jsoup.Connection.Response response = fetchWithRetry(current.url);
                    cookieJar.putAll(response.cookies());
                    // Use the final URL after redirects as the canonical URL for this page
                    String effectiveUrl = response.url().toString();
                    // If the server redirected us, mark the effective URL as queued so that
                    // any other page linking directly to it is not visited a second time.
                    if (!effectiveUrl.equals(current.url)) {
                        dedup.markQueued(effectiveUrl);
                    }

                    crawlResult.visitedCount++;

                    // If the seed URL permanently redirected to a different domain (e.g. an old
                    // domain that migrated), switch domain scoping to the destination domain so
                    // links on the new domain are followed rather than silently discarded.
                    if (crawlResult.visitedCount == 1 && !isSameDomain(extractHost(effectiveUrl))) {
                        String newHost = extractHost(effectiveUrl);
                        startDomain = newHost.startsWith("www.") ? newHost.substring(4) : newHost;
                        allowSubdomains = newHost.startsWith("www.");
                    }

                    String contentLengthHeader = response.header("Content-Length");
                    if (contentLengthHeader != null) {
                        try {
                            long length = Long.parseLong(contentLengthHeader);
                            if (length > options.getMaxBytes()) {
                                crawlResult.incrementError(CrawlResult.ErrorType.SKIPPED_SIZE);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    byte[] body = response.bodyAsBytes();
                    if (body.length > options.getMaxBytes()) {
                        crawlResult.incrementError(CrawlResult.ErrorType.SKIPPED_SIZE);
                        continue;
                    }

                    String contentType = response.contentType();
                    String content;
                    List<String> links = new ArrayList<>();

                    if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                        Document doc = response.parse();

                        if (doc.title().contains("Just a moment...") || doc.text().contains("Enable JavaScript and cookies to continue")) {
                            crawlResult.addBlocked(effectiveUrl, "Cloudflare/Bot protection challenge");
                            continue;
                        }

                        crawlResult.parsedCount++;
                        content = extractor.extractTextFromHtml(doc);
                        if (current.depth < options.getDepth()) {
                            links = extractor.extractLinks(doc, body, effectiveUrl);
                        }

                        if (PlaywrightRenderer.isSpa(doc)) {
                            if (spaRenderingEnabled == null) {
                                spaRenderingEnabled = promptSpaConsent(effectiveUrl);
                            }
                            if (spaRenderingEnabled) {
                                if (renderer == null) {
                                    renderer = new PlaywrightRenderer(options.getTimeoutMs(), options.isInsecure(), options.getBrowser());
                                }
                                PlaywrightRenderer.RenderedPage rendered = renderer.render(effectiveUrl, cookieJar);
                                if (rendered != null) {
                                    content = rendered.text();
                                    if (current.depth < options.getDepth()) {
                                        List<String> allRenderedLinks = new ArrayList<>(rendered.links());
                                        allRenderedLinks.addAll(rendered.docLinks());
                                        links = allRenderedLinks.stream()
                                                .map(href -> UrlUtils.normalizeUrl(href, effectiveUrl))
                                                .filter(l -> !l.isEmpty() && !UrlUtils.isIgnoredLink(l))
                                                .distinct()
                                                .collect(Collectors.toList());
                                    }
                                }
                            }
                        }
                    } else {
                        content = extractor.extractTextFromBinary(body, effectiveUrl, contentType);
                        crawlResult.parsedCount++;
                        crawlResult.docsCount++;
                    }

                    int count = matchEngine.countMatches(content, options.getKeyword(), options.getMode());
                    if (count > 0) {
                        crawlResult.addMatch(effectiveUrl, count);
                    }

                    if (current.depth < options.getDepth()) {
                        for (String link : links) {
                            if (!options.isAllowExternal()) {
                                String linkHost = extractHost(link);
                                if (!isSameDomain(linkHost)) {
                                    continue;
                                }
                            }

                            if (!dedup.isDuplicate(link) && crawlResult.visitedCount + queue.size() < options.getMaxPages()) {
                                dedup.markQueued(link);
                                if (options.isDfs()) {
                                    queue.addFirst(new UrlDepth(link, current.depth + 1));
                                } else {
                                    queue.addLast(new UrlDepth(link, current.depth + 1));
                                }
                            }
                        }
                    }

                } catch (org.jsoup.HttpStatusException e) {
                    if (e.getStatusCode() == HTTP_FORBIDDEN || e.getStatusCode() == HTTP_TOO_MANY_REQUESTS) {
                        crawlResult.addBlocked(current.url, "HTTP " + e.getStatusCode() + " (Access Denied/Rate Limited)");
                    } else {
                        crawlResult.addNetworkError("HTTP " + e.getStatusCode());
                    }
                } catch (Exception e) {
                    crawlResult.addNetworkError(e);
                }

                int totalMatches = crawlResult.getTotalMatches();
                printProgress(current.url, crawlResult.visitedCount, totalMatches);

                if (options.getMaxHits() > 0 && totalMatches >= options.getMaxHits()) {
                    crawlResult.stoppedAtMaxHits = options.getMaxHits();
                    break;
                }
            }
        } finally {
            if (renderer != null) renderer.close();
        }

        System.err.print("\r" + " ".repeat(110) + "\r");
        return crawlResult;
    }

    /**
     * Fetches a URL, retrying up to MAX_RETRIES times on HTTP 429 (rate limited).
     * Waits 2s before the first retry, 4s before the second, or the value from
     * the Retry-After response header (integer seconds) if provided.
     * All requests share the session cookie jar.
     */
    private org.jsoup.Connection.Response fetchWithRetry(String url) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            org.jsoup.Connection conn = Jsoup.connect(url)
                    .timeout(options.getTimeoutMs())
                    .maxBodySize(maxBodySize)
                    .followRedirects(true)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true) // so we can read Retry-After before deciding to retry
                    .cookies(cookieJar)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9,bs;q=0.8,sr;q=0.7,hr;q=0.6")
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
            if (insecureSslFactory != null) conn.sslSocketFactory(insecureSslFactory);
            org.jsoup.Connection.Response response = conn.execute();
            int status = response.statusCode();

            if (status == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
                long waitMs = parseRetryAfter(response.header("Retry-After"), 1000L << attempt);
                System.err.printf("\r%-110s",
                        "  ⏸  Rate limited — waiting " + (waitMs / 1000) + "s before retry "
                        + attempt + "/" + (MAX_RETRIES - 1) + "  |  " + url);
                Thread.sleep(waitMs);
            } else if (status >= 400) {
                throw new org.jsoup.HttpStatusException("HTTP error fetching URL", status, url);
            } else {
                return response;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static long parseRetryAfter(String header, long defaultMs) {
        if (header != null && !header.isBlank()) {
            try {
                return Math.min(Long.parseLong(header.trim()) * 1000L, 60_000L);
            } catch (NumberFormatException ignored) {}
        }
        return defaultMs;
    }

    private SSLSocketFactory buildInsecureSslFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            System.err.println("Warning: failed to configure insecure SSL — " + e.getMessage());
            return null;
        }
    }

    private boolean promptSpaConsent(String url) {
        System.err.println();
        System.err.println("  The page at " + url);
        System.err.println("  is a JavaScript-rendered single-page application (Angular, React, or Vue).");
        System.err.println("  Its content is not visible in a plain HTML fetch and will return no results");
        System.err.println("  without a headless browser.");
        System.err.println();
        System.err.println("  WebGrep can render it automatically. A compatible browser will be used");
        System.err.println("  if one is already installed; otherwise a one-time download (~105 MB) is");
        System.err.println("  required.");
        System.err.println();
        System.err.print("  Enable JavaScript rendering for this session? [Y/n]: ");

        try {
            java.io.Console console = System.console();
            if (console != null) {
                String line = console.readLine();
                if (line != null && line.trim().equalsIgnoreCase("n")) {
                    System.err.println();
                    System.err.println("  JavaScript rendering disabled — SPA pages will return no content.");
                    System.err.println();
                    return false;
                }
            }
        } catch (Exception ignored) {}
        System.err.println();
        return true;
    }

    private void printProgress(String currentUrl, int visited, int matches) {
        String truncated = currentUrl.length() > 60 ? currentUrl.substring(0, 57) + "..." : currentUrl;
        String line = String.format("%c  %d pages visited  |  %d matches found  |  %s",
                SPINNER[spinnerIdx++ % SPINNER.length], visited, matches, truncated);
        System.err.printf("\r%-110s", line);
    }

    private record UrlDepth(String url, int depth) {}
}
