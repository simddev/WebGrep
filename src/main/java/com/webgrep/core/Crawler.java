package com.webgrep.core;

import com.webgrep.config.CliOptions;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.utils.ConsoleText;
import com.webgrep.utils.UrlUtils;
import org.jsoup.Connection;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Crawls a website starting from a seed URL and searches every visited page for a keyword.
 *
 * <p>The crawler supports both breadth-first (default) and depth-first traversal, configurable
 * politeness delays, per-request body size limits, and automatic retry with exponential backoff
 * on HTTP 429 (Too Many Requests) responses. Cookies are kept in a {@link CookieJar} that
 * follows browser rules, so pages that rely on a session stay accessible without a cookie ever
 * reaching a host it was not meant for.
 *
 * <h2>Domain scoping rules</h2>
 * <ul>
 *   <li>If the seed URL starts with {@code www.}, all subdomains of the root domain are followed
 *       (e.g. seed {@code www.example.com} also follows {@code docs.example.com}).</li>
 *   <li>Otherwise, only the exact domain and its {@code www.} alias are followed.</li>
 *   <li>If the seed itself redirects to another domain - a site that moved - the scope moves
 *       with it.</li>
 *   <li>Any other redirect that would leave the scope is not followed; it is listed in the
 *       results instead.</li>
 *   <li>Use {@code --allow-external} to disable domain scoping entirely.</li>
 * </ul>
 *
 * <h2>Link classification</h2>
 * <p>Links discovered on each page are split into two categories:
 * <ul>
 *   <li><b>Navigation links</b> - HTML pages; only enqueued when within the depth limit.</li>
 *   <li><b>Document links</b> - URLs ending in known document extensions (PDF, DOCX, etc.);
 *       always enqueued regardless of depth, since documents are leaf nodes that do not
 *       expand the crawl frontier exponentially.</li>
 * </ul>
 *
 * <p>HTML pages are parsed with Jsoup; all other content types are passed to
 * {@link ContentExtractor} for binary extraction via Apache Tika.
 */
public class Crawler {

    /** Characters cycled to draw the rotating progress spinner in the terminal. */
    private static final char[] SPINNER = {'|', '/', '-', '\\'};

    /** Maximum number of attempts for a single URL on HTTP 429 (rate limit) responses. */
    private static final int MAX_RETRIES = 3;

    /** Maximum redirect hops followed for one URL; a longer chain is reported as an error. */
    private static final int MAX_REDIRECTS = 20;

    /**
     * Upper bound on the number of URLs waiting in the queue. This guards memory on huge sites;
     * it is not the page budget, which {@code --max-pages} sets. A link found while the queue is
     * full is not lost for good: it is queued if found again later.
     */
    private static final int MAX_QUEUED_URLS = 100_000;

    /** HTTP status code for Forbidden - triggers a blocked entry, not a retry. */
    private static final int HTTP_FORBIDDEN = 403;

    /** HTTP status code for Too Many Requests - triggers exponential-backoff retries. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Index into {@link #SPINNER}; incremented after each page to advance the animation. */
    private int spinnerIdx = 0;

    /**
     * Cookies received during the crawl, shared with the SPA renderer. Each request, including
     * every redirect hop, sends only the cookies whose domain, path and Secure attributes allow it.
     */
    private final CookieJar cookieJar = new CookieJar();

    /**
     * Three-state flag tracking whether the user has consented to SPA rendering.
     * {@code null} = not yet asked; {@code true} = consented (or non-interactive);
     * {@code false} = declined.
     */
    private Boolean spaRenderingEnabled = null;

    /** Parsed CLI options; used to read depth, max-pages, delay, browser preference, etc. */
    private final CliOptions options;

    /** Shared text extractor for HTML and binary documents. */
    private final ContentExtractor extractor;

    /** Shared keyword matcher; supports default, exact, and fuzzy modes. */
    private final MatchEngine matchEngine;

    /**
     * Root domain of the seed URL used for domain scoping.
     * If the seed was {@code www.example.com}, this is {@code example.com}.
     * If the seed was {@code docs.example.com}, this is {@code docs.example.com}.
     */
    private String startDomain;

    /**
     * Whether to allow all subdomains of {@link #startDomain}.
     * {@code true} when the seed URL starts with {@code www.}; {@code false} otherwise.
     */
    private boolean allowSubdomains;

    /**
     * Maximum size of a response body in bytes, cast to {@code int} for Jsoup's API.
     * Derived from {@code --max-bytes}.
     */
    private final int maxBodySize;

    /** URL deduplicator that prevents the same page from being visited twice. */
    private final UrlDeduplicator dedup;

    /**
     * Insecure SSL socket factory used when {@code --insecure} is active.
     * {@code null} when SSL verification is enabled (the default).
     */
    private final SSLSocketFactory insecureSslFactory;

    /**
     * The JVM-wide hostname verifier that was in effect before {@code --insecure} replaced it.
     * Restored in the {@code finally} block of {@link #crawl()} after the crawl ends.
     * {@code null} when not in insecure mode.
     */
    private final HostnameVerifier originalHostnameVerifier;

    /**
     * Signals that a redirect would leave the crawl's domain scope and was not followed.
     */
    private static final class OffsiteRedirectException extends IOException {

        /** The off-site URL the redirect pointed to. */
        private final String target;

        /**
         * Creates the exception for a redirect that was not followed.
         *
         * @param target the off-site URL the redirect pointed to.
         */
        OffsiteRedirectException(String target) {
            super("Redirect to another site: " + target);
            this.target = target;
        }
    }

    /**
     * Constructs a new {@code Crawler} from the given options and shared components.
     *
     * <p>Determines the starting domain and subdomain policy from the seed URL.
     * When {@code --insecure} is set, builds an all-trusting {@link SSLSocketFactory} and
     * installs a permissive hostname verifier globally (Jsoup has no per-connection API for this).
     *
     * @param options     parsed and validated CLI options.
     * @param extractor   shared text extractor for HTML and binary content.
     * @param matchEngine shared keyword matcher.
     */
    public Crawler(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine) {
        this.options = options;
        this.extractor = extractor;
        this.matchEngine = matchEngine;
        switchScopeTo(extractHost(UrlUtils.normalizeUrl(options.getUrl(), null)));
        // Set Jsoup's cap one byte above the user limit so that a body truncated at exactly
        // maxBytes bytes is distinguishable from a legitimate response of that exact size.
        // The body-length check below uses > maxBytes (not >= maxBodySize) to detect truncation.
        // Clamp maxBytes to INT_MAX-1 first so the +1 never overflows (e.g. --max-bytes Long.MAX_VALUE).
        this.maxBodySize = (int) Math.min(options.getMaxBytes(), (long) Integer.MAX_VALUE - 1) + 1;
        this.dedup = new UrlDeduplicator(options.isAllUrls());

        if (options.isInsecure()) {
            this.insecureSslFactory = buildInsecureSslFactory();
            // Jsoup has no per-connection hostname verifier API; this global setter is the
            // minimal remaining side effect of --insecure. It only runs when explicitly requested.
            this.originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } else {
            this.insecureSslFactory = null;
            this.originalHostnameVerifier = null;
        }
    }

    /**
     * Parses the host (domain name) out of an absolute URL string.
     *
     * @param url a normalised absolute URL.
     * @return the lowercased hostname, or {@code ""} if the URL is malformed.
     */
    private String extractHost(String url) {
        try {
            return new URL(url).getHost().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Sets the domain scope from a host: a {@code www.} host opens the scope to every subdomain
     * of its root domain; any other host is followed together with its {@code www.} alias.
     *
     * @param host the lowercased host of the seed, or of the site the seed redirected to.
     */
    private void switchScopeTo(String host) {
        boolean hasWww = host.startsWith("www.");
        this.startDomain = hasWww ? host.substring(4) : host;
        this.allowSubdomains = hasWww;
    }

    /**
     * Returns {@code true} if {@code linkHost} is within the allowed domain scope.
     *
     * <p>When {@link #allowSubdomains} is {@code true} (seed had {@code www.}), all subdomains
     * of {@link #startDomain} are allowed (e.g. {@code en.example.com}, {@code docs.example.com}).
     * When {@code false}, only the exact domain and its {@code www.} alias are allowed.
     *
     * @param linkHost the lowercased hostname of the link to test.
     * @return {@code true} if the link is within scope and should be followed.
     */
    private boolean isSameDomain(String linkHost) {
        String h = linkHost.toLowerCase(Locale.ROOT);
        if (allowSubdomains) {
            // Seed had www. (e.g. www.wikipedia.org) - allow all subdomains of the root domain
            // so en.wikipedia.org, de.wikipedia.org etc. are all followed.
            return h.equals(startDomain) || h.endsWith("." + startDomain);
        }
        // Seed had no www. - only allow the exact domain and its www. alias.
        return h.equals(startDomain) || h.equals("www." + startDomain);
    }

    /**
     * Executes the web crawl and returns the accumulated results.
     *
     * <p>The crawl loop dequeues one {@link UrlDepth} at a time, fetches the URL, extracts
     * text and links, searches for the keyword, and enqueues newly discovered links. The loop
     * terminates when the queue is empty, {@code --max-pages} pages have been visited, or
     * {@code --max-hits} is reached. Only pages actually visited count towards
     * {@code --max-pages}; a link that turns out to be broken does not use up the budget.
     *
     * <p>On each iteration:
     * <ol>
     *   <li>The URL is fetched via {@link #fetch}, which follows redirects hop by hop and stops
     *       at one that would leave the domain scope.</li>
     *   <li>The final (post-redirect) URL is used as the canonical URL for this page.</li>
     *   <li>If it is an HTML page and looks like a SPA, {@link PlaywrightRenderer} is invoked.</li>
     *   <li>The extracted text is searched and results are stored in {@code crawlResult}.</li>
     *   <li>A zero-delay {@code <meta http-equiv="refresh">} target is enqueued at the same depth,
     *       document links regardless of depth, navigation links only within depth.</li>
     * </ol>
     *
     * <p>The {@link PlaywrightRenderer} is closed in a {@code finally} block, and the global
     * hostname verifier is restored if {@code --insecure} was used.
     *
     * @return a {@link CrawlResult} containing all statistics, matches, and errors.
     */
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
                boolean isSeed = crawlResult.visitedCount == 0;

                try {
                    if (crawlResult.visitedCount > 0) Thread.sleep(options.getDelayMs());

                    // The seed may redirect anywhere: a site that moved to a new domain is still
                    // the site the user asked for, and the scope moves with it below.
                    Connection.Response response = fetch(current.url, options.isAllowExternal() || isSeed);
                    // Use the final URL after redirects as the canonical URL for this page.
                    // Normalise it so the dedup check below compares on the same canonical form.
                    String rawEffective = response.url().toString();
                    String normalized = UrlUtils.normalizeUrl(rawEffective, null);
                    String effectiveUrl = normalized.isEmpty() ? rawEffective : normalized;
                    if (!effectiveUrl.equals(current.url)) {
                        // Two queued URLs may redirect to the same target; skip content processing
                        // for the second arrival so matches are not double-counted.
                        if (dedup.isDuplicate(effectiveUrl)) {
                            closeQuietly(response);
                            crawlResult.visitedCount++;
                            continue;
                        }
                        dedup.markQueued(effectiveUrl);
                    }

                    crawlResult.visitedCount++;

                    // If the seed URL redirected to a different domain (e.g. an old domain that
                    // migrated), switch domain scoping to the destination domain so links on the
                    // new domain are followed rather than silently discarded.
                    if (crawlResult.visitedCount == 1 && !isSameDomain(extractHost(effectiveUrl))) {
                        switchScopeTo(extractHost(effectiveUrl));
                    }

                    String contentLengthHeader = response.header("Content-Length");
                    if (contentLengthHeader != null) {
                        try {
                            long length = Long.parseLong(contentLengthHeader);
                            if (length > options.getMaxBytes()) {
                                closeQuietly(response);
                                crawlResult.incrementError(CrawlResult.ErrorType.SKIPPED_SIZE);
                                continue;
                            }
                        } catch (NumberFormatException ignored) {}
                    }

                    byte[] body = response.bodyAsBytes();
                    // body.length can exceed maxBytes because Jsoup's download cap is maxBytes+1;
                    // a body of exactly maxBodySize bytes means Jsoup truncated the response.
                    if (body.length > options.getMaxBytes()) {
                        crawlResult.incrementError(CrawlResult.ErrorType.SKIPPED_SIZE);
                        continue;
                    }

                    String contentType = response.contentType();
                    String content;
                    // navLinks respect the depth limit; docLinksToEnqueue bypass it because
                    // documents are leaves - they don't spawn further crawl levels.
                    List<String> navLinks = new ArrayList<>();
                    List<String> docLinksToEnqueue = new ArrayList<>();
                    String refreshTarget = null;

                    if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/xhtml+xml"))) {
                        Document doc;
                        try {
                            doc = response.parse();
                        } catch (Exception e) {
                            crawlResult.incrementError(CrawlResult.ErrorType.PARSE_ERROR);
                            continue;
                        }

                        // Extract once and run the bot-challenge check against the result.
                        // Calling doc.text() for the check walked the whole document a second
                        // time, doubling text-extraction cost on every HTML page.
                        content = extractor.extractTextFromHtml(doc);
                        if (doc.title().contains("Just a moment...")
                                || content.contains("Enable JavaScript and cookies to continue")) {
                            crawlResult.addBlocked(effectiveUrl, "Cloudflare/Bot protection challenge");
                            continue;
                        }

                        crawlResult.parsedCount++;
                        // Split Jsoup links into document links (depth-free) and navigation links.
                        for (String l : extractor.extractLinks(doc, body, effectiveUrl)) {
                            if (UrlUtils.isDocumentLink(l)) docLinksToEnqueue.add(l);
                            else navLinks.add(l);
                        }

                        // A zero-delay <meta http-equiv="refresh"> is an immediate navigation in
                        // any browser, but redirect handling only covers HTTP 3xx. Such a stub
                        // page carries no text and often no <a href> either, so without this the
                        // crawl reports zero matches with nothing to explain why.
                        refreshTarget = metaRefreshTarget(doc, effectiveUrl);

                        if (!Boolean.FALSE.equals(spaRenderingEnabled) && PlaywrightRenderer.isSpa(doc)) {
                            if (spaRenderingEnabled == null) {
                                spaRenderingEnabled = promptSpaConsent(effectiveUrl);
                            }
                            if (spaRenderingEnabled) {
                                if (renderer == null) {
                                    renderer = new PlaywrightRenderer(options.getTimeoutMs(), options.isInsecure(), options.getBrowser());
                                }
                                PlaywrightRenderer.RenderedPage rendered = renderer.render(effectiveUrl, cookieJar.all());
                                if (rendered != null) {
                                    content = rendered.text();
                                    rendered.cookies().forEach(cookieJar::add);
                                    // Rendered docLinks (JSON-intercepted + a[download] DOM links) bypass depth.
                                    rendered.docLinks().stream()
                                            .map(href -> UrlUtils.normalizeUrl(href, effectiveUrl))
                                            .filter(l -> !l.isEmpty() && !UrlUtils.isIgnoredLink(l))
                                            .distinct()
                                            .forEach(docLinksToEnqueue::add);
                                    // Rendered navigation links replace Jsoup nav links (richer source).
                                    navLinks = rendered.links().stream()
                                            .map(href -> UrlUtils.normalizeUrl(href, effectiveUrl))
                                            .filter(l -> !l.isEmpty() && !UrlUtils.isIgnoredLink(l))
                                            .distinct()
                                            .collect(Collectors.toList());
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
                        crawlResult.addSnippets(effectiveUrl,
                                matchEngine.findSnippets(content, options.getKeyword(), options.getMode(), 3));
                    }

                    if (refreshTarget != null) {
                        boolean inScope = options.isAllowExternal() || isSameDomain(extractHost(refreshTarget));
                        if (!inScope && crawlResult.visitedCount == 1) {
                            // A seed page that immediately sends the browser to another site is a
                            // redirect like an HTTP one, so the scope moves with it.
                            switchScopeTo(extractHost(refreshTarget));
                            inScope = true;
                        }
                        if (!inScope) {
                            crawlResult.addOffsiteRedirect(effectiveUrl, refreshTarget);
                        } else if (!dedup.isDuplicate(refreshTarget) && queue.size() < MAX_QUEUED_URLS) {
                            dedup.markQueued(refreshTarget);
                            // Queued at the current depth, not one level down: the target is the
                            // page a browser would show here, so its links are this page's links.
                            UrlDepth target = new UrlDepth(refreshTarget, current.depth);
                            if (options.isDfs()) queue.addFirst(target);
                            else queue.addLast(target);
                        }
                    }

                    // Enqueue document links regardless of depth (documents are search targets,
                    // not new crawl levels - they don't multiply the frontier exponentially).
                    for (String docLink : docLinksToEnqueue) {
                        if (!options.isAllowExternal()) {
                            if (!isSameDomain(extractHost(docLink))) continue;
                        }
                        if (!dedup.isDuplicate(docLink) && queue.size() < MAX_QUEUED_URLS) {
                            dedup.markQueued(docLink);
                            queue.addLast(new UrlDepth(docLink, current.depth + 1));
                        }
                    }

                    // Enqueue navigation links only within the depth limit.
                    if (current.depth < options.getDepth()) {
                        for (String link : navLinks) {
                            if (!options.isAllowExternal()) {
                                String linkHost = extractHost(link);
                                if (!isSameDomain(linkHost)) {
                                    continue;
                                }
                            }

                            if (!dedup.isDuplicate(link) && queue.size() < MAX_QUEUED_URLS) {
                                dedup.markQueued(link);
                                if (options.isDfs()) {
                                    queue.addFirst(new UrlDepth(link, current.depth + 1));
                                } else {
                                    queue.addLast(new UrlDepth(link, current.depth + 1));
                                }
                            }
                        }
                    }

                } catch (OffsiteRedirectException e) {
                    crawlResult.addOffsiteRedirect(current.url, e.target);
                } catch (HttpStatusException e) {
                    if (e.getStatusCode() == HTTP_FORBIDDEN || e.getStatusCode() == HTTP_TOO_MANY_REQUESTS) {
                        crawlResult.addBlocked(current.url, "HTTP " + e.getStatusCode() + " (Access Denied/Rate Limited)");
                    } else {
                        crawlResult.addNetworkError("HTTP " + e.getStatusCode());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
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
            if (originalHostnameVerifier != null) {
                HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier);
            }
        }

        System.err.print("\r" + " ".repeat(110) + "\r");
        return crawlResult;
    }

    /**
     * Fetches a URL, following redirects one hop at a time.
     *
     * <p>Redirects are followed here rather than by Jsoup for two reasons. Each hop is a new
     * request whose {@code Cookie} header the {@link CookieJar} builds for that hop's own URL,
     * so a session cookie for one host is never carried along to the next. And each hop's
     * target is checked against the domain scope before anything is sent to it.
     *
     * @param url          the URL to fetch.
     * @param allowOffsite whether a redirect may leave the domain scope - true for the seed and
     *                     under {@code --allow-external}.
     * @return the final, non-redirect response (status below 400).
     * @throws OffsiteRedirectException if a redirect would leave the scope and that is not allowed.
     * @throws Exception on network errors, HTTP errors, or more than {@link #MAX_REDIRECTS} hops.
     */
    private Connection.Response fetch(String url, boolean allowOffsite) throws Exception {
        String current = url;
        for (int hops = 0; ; hops++) {
            Connection.Response response = fetchWithRetry(current);
            String target = redirectTarget(response, current);
            if (target == null) return response;
            closeQuietly(response);
            if (hops >= MAX_REDIRECTS) throw new IOException("Too many redirects");
            if (!allowOffsite && !isSameDomain(extractHost(target))) throw new OffsiteRedirectException(target);
            current = target;
        }
    }

    /**
     * Returns where a response redirects to, or {@code null} if it is not a redirect.
     *
     * @param response the response to inspect.
     * @param url      the URL it was fetched from, for resolving a relative {@code Location}.
     * @return the normalised absolute target URL, or {@code null} for a non-redirect response
     *         (including a 3xx without a {@code Location} header).
     * @throws IOException if the target is not an http(s) URL.
     */
    private static String redirectTarget(Connection.Response response, String url) throws IOException {
        int status = response.statusCode();
        if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
        String location = response.header("Location");
        if (location == null || location.isBlank()) return null;
        String target = UrlUtils.normalizeUrl(location, url);
        if (target.isEmpty()) throw new IOException("Unsupported redirect target");
        return target;
    }

    /**
     * Releases the connection behind a response whose body will not be read.
     *
     * @param response the response to discard.
     */
    private static void closeQuietly(Connection.Response response) {
        try {
            response.bodyStream().close();
        } catch (Exception ignored) {
            // No body, or already read and closed.
        }
    }

    /**
     * Makes one request, retrying up to {@link #MAX_RETRIES} times in total on HTTP 429.
     *
     * <p>On HTTP 429, the crawler waits before retrying. The wait time is taken from the
     * {@code Retry-After} response header if present (interpreted as integer seconds, capped at
     * 60 seconds), otherwise exponential backoff is used: 2 seconds before retry 1, 4 seconds
     * before retry 2. Other HTTP errors (4xx, 5xx) are thrown immediately as
     * {@link HttpStatusException}.
     *
     * <p>Every response's {@code Set-Cookie} headers go into the {@link CookieJar}, error and
     * redirect responses included (some challenge flows set a cookie on a 429 that must be sent
     * back on the retry). Redirects are not followed here; see {@link #fetch}. All requests carry
     * browser-like headers ({@code User-Agent}, {@code Accept}, {@code Accept-Language}) to avoid
     * being blocked by basic bot-detection.
     *
     * @param url the URL to fetch.
     * @return the response (status below 400; may be a redirect).
     * @throws Exception on network errors, non-retryable HTTP errors, or exhausted retries.
     */
    private Connection.Response fetchWithRetry(String url) throws Exception {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            Connection conn = Jsoup.connect(url)
                    .timeout(options.getTimeoutMs())
                    .maxBodySize(maxBodySize)
                    .followRedirects(false)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true) // so we can read Retry-After before deciding to retry
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                    .header("Accept-Language", "en-US,en;q=0.9,bs;q=0.8,sr;q=0.7,hr;q=0.6")
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36");
            String cookieHeader = cookieJar.header(url);
            if (cookieHeader != null) conn.header("Cookie", cookieHeader);
            if (insecureSslFactory != null) conn.sslSocketFactory(insecureSslFactory);
            Connection.Response response = conn.execute();
            cookieJar.store(url, response.headers("Set-Cookie"));
            int status = response.statusCode();

            if (status == HTTP_TOO_MANY_REQUESTS && attempt < MAX_RETRIES) {
                long waitMs = parseRetryAfter(response.header("Retry-After"), 1000L << attempt);
                closeQuietly(response);
                System.err.printf("\r%-110s",
                        "  ⏸  Rate limited - waiting " + (waitMs / 1000) + "s before retry "
                        + attempt + "/" + (MAX_RETRIES - 1) + "  |  " + ConsoleText.safe(url));
                Thread.sleep(waitMs);
            } else if (status >= 400) {
                closeQuietly(response);
                throw new HttpStatusException("HTTP error fetching URL", status, url);
            } else {
                return response;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Extracts the target of a zero-delay {@code <meta http-equiv="refresh">} tag, if present.
     *
     * <p>Only an immediate refresh (delay {@code 0}) is treated as a redirect. A page that
     * refreshes itself after a delay - a live scoreboard, a "returning to home in 30 seconds"
     * notice - is a normal page, and following that target would add noise rather than content.
     *
     * <p>Package-private so unit tests can exercise the parsing rules directly.
     *
     * @param doc     the parsed HTML document.
     * @param baseUrl the canonical URL of the page, used to resolve a relative target.
     * @return the normalised absolute redirect target, or {@code null} if there is no usable
     *         zero-delay refresh.
     */
    static String metaRefreshTarget(Document doc, String baseUrl) {
        org.jsoup.nodes.Element el = doc.selectFirst("meta[http-equiv=refresh]");
        if (el == null) return null;

        String content = el.attr("content");
        int semicolon = content.indexOf(';');
        if (semicolon < 0) return null;
        try {
            if (Double.parseDouble(content.substring(0, semicolon).trim()) > 0) return null;
        } catch (NumberFormatException e) {
            return null;
        }

        String rest = content.substring(semicolon + 1).trim();
        if (!rest.regionMatches(true, 0, "url", 0, 3)) return null;
        int equals = rest.indexOf('=');
        if (equals < 0) return null;
        String raw = rest.substring(equals + 1).trim();
        if (raw.length() >= 2 && (raw.charAt(0) == '"' || raw.charAt(0) == '\'')) {
            int close = raw.indexOf(raw.charAt(0), 1);
            raw = close > 0 ? raw.substring(1, close) : raw.substring(1);
        }
        if (raw.isEmpty()) return null;

        String target = UrlUtils.normalizeUrl(raw, baseUrl);
        if (target.isEmpty() || UrlUtils.isIgnoredLink(target) || target.equals(baseUrl)) return null;
        return target;
    }

    /**
     * Parses the {@code Retry-After} HTTP response header into a wait duration in milliseconds.
     *
     * <p>Only the integer-seconds form of {@code Retry-After} is handled (date-string form is
     * not supported). The parsed value is capped at 60 000 ms (60 seconds) to prevent a server
     * from forcing an indefinitely long pause.
     *
     * @param header    the raw {@code Retry-After} header value, or {@code null} if absent.
     * @param defaultMs the fallback duration in milliseconds used when the header is absent or unparseable.
     * @return the wait duration in milliseconds.
     */
    private static long parseRetryAfter(String header, long defaultMs) {
        if (header != null && !header.isBlank()) {
            try {
                return Math.max(0L, Math.min(Long.parseLong(header.trim()) * 1000L, 60_000L));
            } catch (NumberFormatException ignored) {}
        }
        return defaultMs;
    }

    /**
     * Builds an {@link SSLSocketFactory} that trusts all certificates and ignores all
     * certificate validation errors. Used when {@code --insecure} is active.
     *
     * <p><b>Security warning:</b> this disables all SSL certificate validation and makes the
     * connection vulnerable to man-in-the-middle attacks. Only use for internal or test sites.
     *
     * @return an all-trusting {@link SSLSocketFactory}, or {@code null} if creation fails.
     */
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
            System.err.println("Warning: failed to configure insecure SSL - " + e.getMessage());
            return null;
        }
    }

    /**
     * Prompts the user interactively to confirm whether they want SPA (headless browser)
     * rendering for the current session.
     *
     * <p>In non-interactive mode ({@code System.console() == null} - piped output, CI,
     * Docker), the prompt is skipped and {@code true} is returned automatically. If no browser
     * is available in that case, {@link PlaywrightRenderer} will report its own warning and
     * mark itself unavailable.
     *
     * <p>The question is asked at most once per crawl run; the result is stored in
     * {@link #spaRenderingEnabled} and applied to all subsequent SPA pages.
     *
     * @param url the URL of the first SPA page detected, shown to the user in the prompt.
     * @return {@code true} if the user agrees to enable rendering; {@code false} if they decline.
     */
    private boolean promptSpaConsent(String url) {
        java.io.Console console = System.console();
        if (console == null) {
            // Non-interactive session: proceed silently. If a system browser or
            // Playwright-cached browser is available no download will happen.
            // If no browser is found, PlaywrightRenderer will refuse to download
            // without a TTY and mark itself unavailable - SPA pages will be skipped.
            return true;
        }

        System.err.println();
        System.err.println("  The page at " + ConsoleText.safe(url));
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
            String line = console.readLine();
            if (line != null && line.trim().equalsIgnoreCase("n")) {
                System.err.println();
                System.err.println("  JavaScript rendering disabled - SPA pages will return no content.");
                System.err.println();
                return false;
            }
        } catch (Exception ignored) {}
        System.err.println();
        return true;
    }

    /**
     * Writes a single-line progress indicator to stderr, overwriting the previous line.
     *
     * <p>Uses {@code \r} (carriage return) to stay on the same terminal line so the crawl
     * output doesn't scroll. The line is padded to 110 characters to ensure the previous
     * content is fully erased when shorter URLs replace longer ones.
     *
     * <p>Written to stderr so that JSON output on stdout is not contaminated. The URL is
     * escaped with {@link ConsoleText#safe} before printing.
     *
     * @param currentUrl the URL currently being processed (truncated to 60 chars if long).
     * @param visited    the number of pages visited so far.
     * @param matches    the total number of keyword matches found so far.
     */
    private void printProgress(String currentUrl, int visited, int matches) {
        String url = ConsoleText.safe(currentUrl);
        String truncated = url.length() > 60 ? url.substring(0, 57) + "..." : url;
        String line = String.format("%c  %d pages visited  |  %d matches found  |  %s",
                SPINNER[spinnerIdx++ % SPINNER.length], visited, matches, truncated);
        System.err.printf("\r%-110s", line);
    }

    /**
     * Pairs a URL with the crawl depth at which it was discovered.
     *
     * <p>The depth is used to enforce the {@code --depth} limit: navigation links are only
     * enqueued when {@code depth < options.getDepth()}. Document links are always enqueued
     * regardless of depth and simply record {@code current.depth + 1} for completeness.
     *
     * @param url   the normalised absolute URL.
     * @param depth the crawl depth at which this URL was enqueued (0 = seed URL).
     */
    private record UrlDepth(String url, int depth) {}
}
