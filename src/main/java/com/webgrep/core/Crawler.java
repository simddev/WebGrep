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
import java.util.*;

public class Crawler {
    private static final char[] SPINNER = {'|', '/', '-', '\\'};
    private int spinnerIdx = 0;

    private final CliOptions options;
    private final ContentExtractor extractor;
    private final MatchEngine matchEngine;
    private final String startHost;

    public Crawler(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine) {
        this.options = options;
        this.extractor = extractor;
        this.matchEngine = matchEngine;
        this.startHost = extractHost(UrlUtils.normalizeUrl(options.getUrl(), null));

        if (options.isInsecure()) {
            setupSsl();
        }
    }

    private String extractHost(String url) {
        try {
            return new URL(url).getHost().toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    // All URLs that have been added to the crawl queue (exact URL strings)
    private final Set<String> queued = new HashSet<>();
    // For smart dedup: base path (no query) → canonical key=value pairs from first queuing
    private final Map<String, Set<String>> canonicalPathParams = new HashMap<>();

    private boolean isAlreadyQueued(String url) {
        if (queued.contains(url)) return true;
        if (options.isAllUrls()) return false;

        int q = url.indexOf('?');
        if (q == -1) {
            // No query params: duplicate if we've seen this base path before (with or without params)
            return canonicalPathParams.containsKey(url);
        }

        String base = url.substring(0, q);
        if (!canonicalPathParams.containsKey(base)) return false;

        Set<String> canonical = canonicalPathParams.get(base);
        if (canonical.isEmpty()) return true; // base was visited without params; any parameterized variant is a dup

        // Parse new URL's params as key=value tokens
        Set<String> newParams = new HashSet<>(Arrays.asList(url.substring(q + 1).split("&")));
        // Duplicate if new URL contains all canonical key=value pairs (same keys AND same values)
        // e.g. ?subjkod=207020&r=agenda is a dup of ?subjkod=207020, but ?souborid=1234 is not a dup of ?souborid=9477999
        return newParams.containsAll(canonical);
    }

    private void markQueued(String url) {
        queued.add(url);
        if (options.isAllUrls()) return;

        int q = url.indexOf('?');
        String base = q != -1 ? url.substring(0, q) : url;
        if (!canonicalPathParams.containsKey(base)) {
            Set<String> params = q != -1
                    ? new HashSet<>(Arrays.asList(url.substring(q + 1).split("&")))
                    : Collections.emptySet();
            canonicalPathParams.put(base, params);
        }
    }

    public CrawlResult crawl() {
        CrawlResult crawlResult = new CrawlResult();
        Queue<UrlDepth> queue = new LinkedList<>();

        String normalizedStart = UrlUtils.normalizeUrl(options.getUrl(), null);
        queue.add(new UrlDepth(normalizedStart, 0));
        markQueued(normalizedStart);

        while (!queue.isEmpty() && crawlResult.visitedCount < options.getMaxPages()) {
            UrlDepth current = queue.poll();

            try {
                Thread.sleep(options.getDelayMs());

                org.jsoup.Connection.Response response = Jsoup.connect(current.url)
                        .timeout(options.getTimeoutMs())
                        .maxBodySize((int) Math.min(options.getMaxBytes(), Integer.MAX_VALUE))
                        .followRedirects(true)
                        .ignoreContentType(true)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                        .header("Accept-Language", "en-US,en;q=0.9,bs;q=0.8,sr;q=0.7,hr;q=0.6")
                        .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                        .execute();

                crawlResult.visitedCount++;

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
                    crawlResult.parsedCount++;

                    if (doc.title().contains("Just a moment...") || doc.text().contains("Enable JavaScript and cookies to continue")) {
                        crawlResult.addBlocked(current.url, "Cloudflare/Bot protection challenge");
                    }

                    content = extractor.extractTextFromHtml(doc);
                    if (current.depth < options.getDepth()) {
                        links = extractor.extractLinks(doc, body, current.url);
                    }
                } else {
                    content = extractor.extractTextFromBinary(body, current.url, contentType);
                    crawlResult.parsedCount++;
                }

                int count = matchEngine.countMatches(content, options.getKeyword(), options.getMode());
                if (count > 0) {
                    crawlResult.addMatch(current.url, count);
                }

                if (current.depth < options.getDepth()) {
                    for (String link : links) {
                        if (!options.isAllowExternal()) {
                            String linkHost = extractHost(link);
                            if (!linkHost.equalsIgnoreCase(startHost)) {
                                continue;
                            }
                        }

                        if (!isAlreadyQueued(link) && queued.size() < options.getMaxPages()) {
                            markQueued(link);
                            queue.add(new UrlDepth(link, current.depth + 1));
                        }
                    }
                }

            } catch (org.jsoup.HttpStatusException e) {
                if (e.getStatusCode() == 403 || e.getStatusCode() == 429) {
                    crawlResult.addBlocked(current.url, "HTTP " + e.getStatusCode() + " (Access Denied/Rate Limited)");
                } else {
                    crawlResult.incrementError(CrawlResult.ErrorType.NETWORK_ERROR);
                }
            } catch (Exception e) {
                crawlResult.incrementError(CrawlResult.ErrorType.NETWORK_ERROR);
            }

            int totalMatches = crawlResult.results.values().stream().mapToInt(Integer::intValue).sum();
            printProgress(current.url, crawlResult.visitedCount, totalMatches);
        }

        System.err.print("\r" + " ".repeat(100) + "\r");
        return crawlResult;
    }

    private void setupSsl() {
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
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception ignored) {}
    }

    private void printProgress(String currentUrl, int visited, int matches) {
        String truncated = currentUrl.length() > 60 ? currentUrl.substring(0, 57) + "..." : currentUrl;
        System.err.printf("\r%c  %d pages visited  |  %d matches found  |  %s",
                SPINNER[spinnerIdx++ % SPINNER.length], visited, matches, truncated);
    }

    private record UrlDepth(String url, int depth) {}
}
