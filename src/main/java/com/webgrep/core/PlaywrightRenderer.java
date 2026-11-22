package com.webgrep.core;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.webgrep.utils.ConsoleText;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Falls back to a headless browser for pages that are Angular/React/Vue SPAs.
 *
 * <p>Beyond rendering page text, it intercepts JSON API responses that the SPA makes
 * during load and extracts document download URLs that are not present as plain
 * {@code <a href>} links in the DOM (e.g. click-handler-driven PDF downloads).
 *
 * <h2>Browser resolution order (first that starts wins)</h2>
 * <ol>
 *   <li>System Chromium/Chrome - driven natively via CDP</li>
 *   <li>Playwright's Firefox - from WebGrep's own browser folder, then the shared Playwright cache</li>
 *   <li>Playwright's Chromium - same two places</li>
 *   <li>Prompt the user to download Firefox (default) or Chromium into WebGrep's own folder</li>
 * </ol>
 * Use {@code --browser firefox|chromium} to skip the other browser's tiers.
 *
 * <p>An installed, branded Firefox is not used: Playwright drives Firefox through a protocol
 * that only its own patched build contains, so a system Firefox of any edition fails to start.
 *
 * <h2>Why WebGrep has its own browser folder</h2>
 * <p>Playwright normally shares one browser cache between every program that uses it. Whenever
 * any of them installs a browser, Playwright deletes the cached browsers no living installation
 * claims - and WebGrep's claim points into a temporary directory that is gone once WebGrep exits.
 * Another project's {@code playwright install} would therefore delete WebGrep's browser.
 * Browsers WebGrep downloads go into {@link #browsersPath()} instead, which nothing else
 * touches. Browsers already in the shared cache are still used if they start.
 */
public class PlaywrightRenderer implements AutoCloseable {

    /**
     * Regex that matches relative or absolute URLs ending in a document extension inside JSON
     * string values. Captures both full HTTP URLs and root-relative paths.
     * Supported extensions: {@code pdf}, {@code docx}, {@code xlsx}, {@code odt}, {@code doc},
     * {@code pptx}, {@code csv}.
     */
    private static final Pattern JSON_DOC_URL = Pattern.compile(
            "\"((?:https?://[^\"]+|/[^\"\\s]+)\\.(?:pdf|docx|xlsx|odt|doc|pptx|csv)(?:\\?[^\"]*)?)\"",
            Pattern.CASE_INSENSITIVE);

    /**
     * Regex that matches REST API download endpoints where the terminal path segment is the
     * literal word {@code download}. Catches common patterns like {@code /api/files/123/download}
     * or {@code /soubor/456/download} that appear as strings inside JSON API responses.
     * Requires the path to be at least 4 characters before the final {@code /download} segment
     * to avoid false positives.
     */
    private static final Pattern JSON_DOWNLOAD_URL = Pattern.compile(
            "\"((?:https?://[^\"\\s]{4,}|/[^\"\\s]{4,})/download)\"",
            Pattern.CASE_INSENSITIVE);

    /** The Playwright instance that manages the browser process. Recreated after disconnects. */
    private Playwright playwright;

    /** The launched browser (Chromium or Firefox). Connected via CDP (Chromium) or Playwright protocol (Firefox). */
    private Browser browser;

    /**
     * The persistent browser context kept alive across multiple {@link #render} calls on the
     * same domain. Reusing a context avoids re-downloading and re-parsing the JS bundle for
     * every SPA sub-route.
     */
    private BrowserContext persistentContext;

    /**
     * The single browser tab (Page) kept open within {@link #persistentContext}.
     * Navigated to each new SPA URL instead of opening a new tab each time.
     */
    private Page persistentPage;

    /**
     * The scheme+host of the domain currently loaded in {@link #persistentContext}
     * (e.g. {@code "https://example.com"}). Used to detect when a new domain requires
     * a fresh context.
     */
    private String persistentBaseUrl;

    /**
     * Document download URLs captured by the response interceptor during the current
     * page navigation. Cleared before each {@link #render} call. Thread-safe because the
     * Playwright network thread writes to it concurrently with the main thread.
     */
    private final List<String> interceptedLinks = Collections.synchronizedList(new ArrayList<>());

    /**
     * The scheme+host passed to the response interceptor so it can prefix relative paths
     * found in JSON responses. Updated before each navigation.
     */
    private volatile String interceptorUrlBase = "";

    /**
     * {@code true} after the renderer has failed to initialise (no browser found, browser
     * crashed, etc.). Once set, all subsequent {@link #render} calls return {@code null}
     * immediately rather than trying again.
     */
    private boolean unavailable = false;

    /** Network request timeout in milliseconds, applied to individual page navigations. */
    private final int timeoutMs;

    /** Whether to ignore HTTPS certificate errors in the browser context. Matches {@code --insecure}. */
    private final boolean insecure;

    /**
     * User's browser preference: {@code "firefox"}, {@code "chromium"}, or {@code null} for auto.
     * Controls which tiers of {@link #launchInstalled()} are skipped.
     */
    private final String preferredBrowser;

    /**
     * The data returned by a single successful page render.
     *
     * @param text      the rendered page's searchable text: title, body text and the
     *                  description and keywords meta tags, extracted the same way as for a
     *                  plain HTML page.
     * @param links     all {@code <a href>} URLs found in the rendered page, fully resolved
     *                  via the browser's {@code a.href} DOM property (respects {@code <base href>}).
     * @param docLinks  document download URLs: REST {@code /download} endpoints and file URLs
     *                  intercepted from JSON API responses, plus {@code <a download>} DOM links.
     * @param cookies   cookies of the page's site that the browser holds after the render, with
     *                  their attributes, to be returned to the crawler's {@link CookieJar}.
     */
    public record RenderedPage(String text, List<String> links, List<String> docLinks,
                               List<CookieJar.Cookie> cookies) {}

    /**
     * Constructs a new {@code PlaywrightRenderer}.
     *
     * <p>The renderer is lazy: no browser is launched until the first call to {@link #render}.
     *
     * @param timeoutMs       per-navigation timeout in milliseconds.
     * @param insecure        if {@code true}, HTTPS certificate errors are ignored.
     * @param preferredBrowser browser preference string from {@code --browser}; {@code "auto"}
     *                         is normalised to {@code null} to simplify tier-skip logic.
     */
    public PlaywrightRenderer(int timeoutMs, boolean insecure, String preferredBrowser) {
        this.timeoutMs = timeoutMs;
        this.insecure = insecure;
        this.preferredBrowser = "auto".equalsIgnoreCase(preferredBrowser) ? null : preferredBrowser;
    }

    /**
     * Heuristically detects whether a Jsoup-parsed HTML document is a single-page application
     * (Angular, React, Vue, Next.js, or Nuxt.js) that needs JavaScript to render its content.
     *
     * <p>Detection signals (any one is sufficient):
     * <ul>
     *   <li>{@code <html ng-version="…">} - Angular</li>
     *   <li>{@code <html data-beasties-container>} - Angular SSR shell</li>
     *   <li>{@code <html data-n-head>} - Nuxt.js</li>
     *   <li>{@code [data-reactroot]} element - React</li>
     *   <li>{@code <script id="__NEXT_DATA__">} - Next.js</li>
     *   <li>{@code <app-root>} with &lt; 100 chars of text - Angular empty shell</li>
     *   <li>{@code <div id="root">} or {@code <div id="app">} with &lt; 100 chars - React/Vue shell</li>
     *   <li>Body text &lt; 100 chars + any JS bundle - generic SPA shell</li>
     *   <li>Body text &lt; 300 chars + 2 or more JS bundles - stronger generic SPA signal</li>
     * </ul>
     *
     * @param doc the Jsoup-parsed HTML of the plain HTTP response.
     * @return {@code true} if the page appears to be a JavaScript-rendered SPA.
     */
    public static boolean isSpa(Document doc) {
        org.jsoup.nodes.Element html = doc.selectFirst("html");
        if (html != null && (html.hasAttr("ng-version")
                || html.hasAttr("data-beasties-container")
                || html.hasAttr("data-n-head"))) return true;
        if (!doc.select("[data-reactroot]").isEmpty()) return true;
        if (doc.selectFirst("script#__NEXT_DATA__") != null) return true;
        org.jsoup.nodes.Element appRoot = doc.selectFirst("app-root");
        if (appRoot != null && appRoot.text().length() < 100) return true;
        org.jsoup.nodes.Element rootDiv = doc.selectFirst("div#root, div#app");
        if (rootDiv != null && rootDiv.text().length() < 100) return true;
        String bodyText = doc.body() != null ? doc.body().text() : "";
        // Minimal body (< 100 chars, e.g. "Loading...") + any JS bundle → likely SPA shell.
        // Slightly longer body (< 300 chars) requires 2+ bundles - SPAs always load multiple
        // chunks, whereas a static page with a single analytics script has only one.
        var scripts = doc.select("script[src*='.js']");
        return bodyText.length() < 100 && !scripts.isEmpty()
                || bodyText.length() < 300 && scripts.size() >= 2;
    }

    /**
     * Renders the given URL using a headless browser and returns the visible text, all links,
     * document download URLs, and cookies.
     *
     * <p>A persistent browser context is reused across consecutive calls to the same domain
     * (same scheme+host). For SPA sub-routes this means the JavaScript bundle is already loaded
     * and the router just swaps components - typically 150 - 400 ms instead of 10 - 15 s per page.
     *
     * <p>A new context is created when:
     * <ul>
     *   <li>{@link #persistentPage} is null or closed (first call, or after a crash).</li>
     *   <li>The URL's base (scheme+host) differs from {@link #persistentBaseUrl}.</li>
     * </ul>
     *
     * <p>Link collection uses {@code a.href} (the DOM property) rather than
     * {@code a.getAttribute('href')} so that relative links are resolved against the page's
     * {@code <base href>} tag. Angular SPAs set {@code <base href="/app/">} and use bare
     * relative hrefs like {@code api/v1/files/123/download} - resolving those manually against
     * the route URL gives the wrong path; {@code a.href} gives the correct absolute URL.
     *
     * <p>Resource blocking: images, fonts, stylesheets, and media are aborted in the browser
     * context. Only {@code document}, {@code script}, {@code xhr}, and {@code fetch} requests
     * are allowed. This significantly speeds up page load and avoids {@code NETWORKIDLE}
     * delays caused by large media assets.
     *
     * @param url     the URL to render.
     * @param cookies the crawler's cookies, given to a new browser context so that
     *                session-dependent SPAs render as they would for the crawler; the browser
     *                sends each one only where its attributes allow.
     * @return a {@link RenderedPage} containing text, links, doc links, and updated cookies;
     *         or {@code null} if rendering is unavailable or the navigation timed out.
     */
    public RenderedPage render(String url, List<CookieJar.Cookie> cookies) {
        if (unavailable) return null;
        try {
            ensureReady();
        } catch (Exception e) {
            unavailable = true;
            String cause = e.getMessage() != null
                    ? e.getMessage().lines().findFirst().orElse("unknown error")
                    : e.getClass().getSimpleName();
            System.err.printf("%n  Warning: SPA rendering unavailable (%s).%n"
                    + "  JavaScript-rendered pages will return no results.%n%n", ConsoleText.safe(cause));
            return null;
        }
        try {
            String urlBase = baseUrl(url);

            // Keep a single BrowserContext and Page alive across renders on the same domain.
            // For SPA sub-routes, this means the JavaScript bundle is already loaded and the
            // router just swaps components - typically 1 - 3 s instead of 10 - 15 s per page.
            boolean needsNewPage = persistentPage == null || persistentPage.isClosed()
                    || !urlBase.equals(persistentBaseUrl);
            if (needsNewPage) {
                closePersistentContext();
                Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                        .setIgnoreHTTPSErrors(insecure);
                persistentContext = browser.newContext(ctxOpts);
                // Block images, fonts, stylesheets and media - they add no text content and
                // slow down navigation + delay NETWORKIDLE by triggering extra resource fetches.
                persistentContext.route("**/*", route -> {
                    String rt = route.request().resourceType();
                    if ("document".equals(rt) || "script".equals(rt)
                            || "xhr".equals(rt) || "fetch".equals(rt)) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
                persistentPage = persistentContext.newPage();
                persistentPage.setDefaultTimeout(timeoutMs);
                persistentPage.onResponse(
                        response -> captureDocLinks(response, this.interceptorUrlBase, this.interceptedLinks));
                passCookies(persistentContext, cookies);
                persistentBaseUrl = urlBase;
            }

            interceptedLinks.clear();
            interceptorUrlBase = urlBase;

            try {
                persistentPage.navigate(url);
            } catch (TimeoutError e) {
                // Navigation itself timed out - return null for this page but keep the
                // renderer alive; a slow page shouldn't disable SPA rendering entirely.
                return null;
            }

            int idleTimeout = needsNewPage ? Math.min(timeoutMs, 15_000) : Math.min(timeoutMs, 5_000);
            // Wait for DOM to be interactive first (always fires, cheap).
            try {
                persistentPage.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(idleTimeout));
            } catch (TimeoutError ignored) {}
            if (needsNewPage) {
                // First navigation to this domain: wait for full network idle so that the
                // initial API responses (e.g. the full document list on the seed page) are
                // captured by the response interceptor before we snapshot links.
                try {
                    persistentPage.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(idleTimeout));
                } catch (TimeoutError ignored) {}
            } else {
                // Subsequent same-domain SPA navigations: the router just swaps components.
                // Wait until the content area has non-trivial text - resolves as soon as the
                // route renders (typically 150 - 400 ms) instead of the full NETWORKIDLE tail.
                try {
                    persistentPage.waitForFunction(
                            "() => { const r = document.querySelector("
                            + "'main, [role=main], router-outlet + *, app-root > *') "
                            + "|| document.body; return r && r.innerText.trim().length > 100; }",
                            null,
                            new Page.WaitForFunctionOptions()
                                    .setTimeout(idleTimeout)
                                    .setPollingInterval(50));
                } catch (TimeoutError ignored) {}
            }

            // Single evaluate round-trip: collect resolved hrefs and the content root's HTML.
            // Previous approach called querySelectorAll then getAttribute per element - one
            // IPC call per anchor (80+ on some pages), adding ~150ms and leaking ElementHandles.
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) persistentPage.evaluate(
                    // Use a.href (the DOM property) instead of a.getAttribute('href') so that
                    // relative URLs are resolved via the page's <base href> tag, not the page URL.
                    // Angular apps set <base href="/app/"> and use hrefs like "api/v1/..." - those
                    // would resolve incorrectly against the current route if we used getAttribute.
                    "() => {"
                    + "  const ls = [], ds = [];"
                    + "  for (const a of document.querySelectorAll('a[href]')) {"
                    + "    const raw = a.getAttribute('href') || '';"
                    + "    if (!raw || raw.startsWith('javascript:') || raw.startsWith('mailto:')"
                    + "        || raw === '#' || raw.startsWith('#')) continue;"
                    + "    const h = a.href;"  // fully resolved by browser against <base href>
                    + "    if (!h || h.startsWith('javascript:') || h.startsWith('mailto:')) continue;"
                    + "    ls.push(h);"
                    // Links marked with the HTML5 download attribute are file downloads, not navigation.
                    + "    if (a.hasAttribute('download')) ds.push(h);"
                    + "  }"
                    + "  const wgRoot = document.querySelector("
                    + "    'main, [role=main], [role=content], app-root, #app, #root, #content'"
                    + "  ) || document.body;"
                    + "  const wgClone = wgRoot ? wgRoot.cloneNode(true) : null;"
                    + "  if (wgClone) wgClone.querySelectorAll('script,style,noscript,template')"
                    + "                      .forEach(n => n.remove());"
                    + "  const wgAttr = s => { const e = document.querySelector(s);"
                    + "                        return e ? (e.getAttribute('content') || '') : ''; };"
                    // The HTML, not textContent: textContent puts nothing between elements, and
                    // framework output has no whitespace between them, so "<h1>Hello</h1><p>World"
                    // would read as "HelloWorld". Jsoup turns the HTML into text instead.
                    + "  return { title: document.title || '', html: wgClone ? wgClone.innerHTML : '',"
                    + "           description: wgAttr('meta[name=description]'),"
                    + "           keywords: wgAttr('meta[name=keywords]'),"
                    + "           links: [...new Set(ls)], downloadLinks: [...new Set(ds)] };"
                    + "}");

            String text = snapshot != null
                    ? renderedText(asString(snapshot.get("title")), asString(snapshot.get("html")),
                                   asString(snapshot.get("description")), asString(snapshot.get("keywords")))
                    : "";
            @SuppressWarnings("unchecked")
            List<String> domLinks = snapshot != null && snapshot.get("links") != null
                    ? (List<String>) snapshot.get("links")
                    : new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<String> domDownloadLinks = snapshot != null && snapshot.get("downloadLinks") != null
                    ? (List<String>) snapshot.get("downloadLinks")
                    : new ArrayList<>();

            List<String> captured = new ArrayList<>(interceptedLinks);
            // Add explicitly-marked download links so they bypass the depth limit in the crawler
            captured.addAll(domDownloadLinks);

            // Return the page's own cookies to the crawler's jar, attributes intact, so they
            // survive context switches and reach the Jsoup fetcher. Third-party cookies set by
            // analytics scripts stay in the browser.
            List<CookieJar.Cookie> contextCookies = new ArrayList<>();
            if (persistentContext != null) {
                try {
                    String pageHost = new URL(url).getHost().toLowerCase(Locale.ROOT);
                    for (Cookie c : persistentContext.cookies()) {
                        if (c.name == null || c.value == null || c.domain == null) continue;
                        CookieJar.Cookie cookie = fromBrowserCookie(c);
                        if (pageHost.equals(cookie.domain()) || pageHost.endsWith("." + cookie.domain())) {
                            contextCookies.add(cookie);
                        }
                    }
                } catch (Exception ignored) {}
            }

            return new RenderedPage(
                    text,
                    domLinks,
                    captured.stream().distinct().collect(Collectors.toList()),
                    contextCookies);
        } catch (Exception e) {
            // Page-level error (JS exception, stale context, etc.) - skip this page but
            // keep the renderer alive so subsequent pages can still be rendered.
            String cause = e.getMessage() != null
                    ? e.getMessage().lines().findFirst().orElse("unknown error")
                    : e.getClass().getSimpleName();
            System.err.printf("%n  Warning: SPA render failed for %s (%s). Skipping page.%n%n",
                    ConsoleText.safe(url), ConsoleText.safe(cause));
            closePersistentContext();
            return null;
        }
    }

    /**
     * Builds the searchable text of a rendered page in the same shape as
     * {@link ContentExtractor#extractTextFromHtml}: title, body text, description, keywords.
     *
     * <p>The body text comes from parsing the rendered HTML with Jsoup, which separates block
     * elements and line breaks with spaces exactly as it does for a plain HTML page.
     *
     * <p>Package-private so that tests can check the text without a browser.
     *
     * @param title       the document title.
     * @param bodyHtml    the inner HTML of the page's content root, with scripts and styles removed.
     * @param description the {@code meta name=description} content.
     * @param keywords    the {@code meta name=keywords} content.
     * @return the page text.
     */
    static String renderedText(String title, String bodyHtml, String description, String keywords) {
        String body = Jsoup.parseBodyFragment(bodyHtml).body().text();
        return title + " " + body + " " + description + " " + keywords;
    }

    /**
     * Reads a value from the DOM snapshot as a string.
     *
     * @param value a value from the DOM snapshot.
     * @return the value as a string; {@code ""} when absent or not a string.
     */
    private static String asString(Object value) {
        return value instanceof String s ? s : "";
    }

    /**
     * Closes the persistent browser context and page, releasing associated resources.
     * Called when switching to a different domain or recovering from a page-level error.
     * Safe to call even if the context is already null.
     */
    private void closePersistentContext() {
        if (persistentContext != null) {
            try { persistentContext.close(); } catch (Exception ignored) {}
            persistentContext = null;
            persistentPage = null;
        }
        persistentBaseUrl = null;
    }

    /**
     * Playwright response event handler. Called on Playwright's internal network thread for
     * every HTTP response the browser receives during a page load.
     *
     * <p>Filters to only JSON responses with status 200, then applies {@link #JSON_DOC_URL} and
     * {@link #JSON_DOWNLOAD_URL} to the response body. Matching URLs are resolved to absolute
     * URLs (prepending {@code urlBase} for root-relative paths) and added to {@code out}.
     *
     * <p>Responses larger than 5 MB are skipped to avoid blocking the network thread while
     * reading a very large API payload.
     *
     * @param response the Playwright response object.
     * @param urlBase  the scheme+host of the current page (e.g. {@code "https://example.com"}).
     * @param out      the thread-safe list to add discovered document URLs to.
     */
    private static void captureDocLinks(Response response, String urlBase, List<String> out) {
        try {
            if (response.status() != 200) return;
            String ct = response.headers().getOrDefault("content-type", "");
            if (!ct.contains("application/json")) return;
            // Skip large JSON responses to avoid blocking the network thread
            String cl = response.headers().getOrDefault("content-length", "");
            if (!cl.isEmpty()) {
                try { if (Long.parseLong(cl) > 5_000_000) return; } catch (NumberFormatException ignored) {}
            }
            String body = response.text();

            for (Pattern pat : new Pattern[]{JSON_DOC_URL, JSON_DOWNLOAD_URL}) {
                Matcher m = pat.matcher(body);
                while (m.find()) {
                    String found = m.group(1);
                    if (found.startsWith("//")) {
                        out.add(urlBase.startsWith("https") ? "https:" + found : "http:" + found);
                    } else if (found.startsWith("/")) {
                        out.add(urlBase + found);
                    } else {
                        out.add(found);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Ensures a browser is launched and ready to render pages.
     *
     * <p>If the browser is already connected, returns immediately. Otherwise tries every browser
     * that needs no download ({@link #launchInstalled()}). If none starts, a download is offered:
     * the browser named by {@code --browser}, or the user's choice (Firefox by default). In a
     * non-interactive session nothing is downloaded; an {@link IOException} explains how to
     * install a browser instead.
     *
     * @throws IOException          if no browser is available and a download cannot be made.
     * @throws InterruptedException if the thread is interrupted while waiting for a download.
     */
    private void ensureReady() throws IOException, InterruptedException {
        if (browser != null && browser.isConnected()) return;

        // Close stale resources before reinitializing (e.g. after browser disconnect).
        // closePersistentContext() must be called first so that persistentContext/persistentPage
        // are nulled out before playwright.close() releases them - otherwise the next render()
        // call finds stale non-null references to already-closed objects.
        closePersistentContext();
        cleanupPlaywright();

        String label = launchInstalled();
        if (label == null) {
            // Refuse to download silently in non-interactive sessions (CI, piped output,
            // Docker). Without a TTY the user cannot confirm the download; they must run
            // 'webgrep --install-browser' explicitly first.
            if (System.console() == null) {
                throw new IOException("No browser available for SPA rendering and no TTY to prompt. "
                        + "Run 'webgrep --install-browser' to install one.");
            }
            String chosen = (preferredBrowser != null) ? preferredBrowser : promptBrowserChoice();
            boolean isChromium = "chromium".equalsIgnoreCase(chosen);
            System.err.printf("%n  Downloading Playwright %s for SPA rendering (~%s MB, one-time)...%n",
                    isChromium ? "Chromium (Google)" : "Firefox (Mozilla)", isChromium ? "120" : "105");
            int exitCode = installBrowser(chosen);
            if (exitCode != 0) {
                throw new IOException("Browser installer exited with code " + exitCode
                        + ". Run '--install-browser' manually to see the full output.");
            }
            System.err.println();
            label = launchInstalled();
            if (label == null) throw new IOException("The browser was installed but could not be started");
        }
        System.err.printf("  SPA renderer: using %s%n", label);
    }

    /**
     * Launches the first browser that needs no download, in tier order, and leaves it in
     * {@link #browser}.
     *
     * <ol>
     *   <li>System Chromium/Chrome (found via {@link BrowserFinder#findChromium}).</li>
     *   <li>Playwright's Firefox, from WebGrep's own folder, then from the shared cache.</li>
     *   <li>Playwright's Chromium, from the same two places.</li>
     * </ol>
     * {@code --browser firefox} skips the Chromium tiers; {@code --browser chromium} skips the
     * Firefox tier. A cached build that does not start - for instance one installed by a
     * different Playwright version - is skipped.
     *
     * @return a description of the browser launched, or {@code null} if none started.
     */
    private String launchInstalled() {
        boolean wantChromium = "chromium".equalsIgnoreCase(preferredBrowser);
        boolean wantFirefox = "firefox".equalsIgnoreCase(preferredBrowser);

        if (!wantFirefox) {
            Optional<Path> sysChr = BrowserFinder.findChromium();
            if (sysChr.isPresent() && tryLaunch(browsersPath(), Playwright::chromium, sysChr.get())) {
                return "system Chromium (" + sysChr.get() + ")";
            }
        }
        if (!wantChromium) {
            for (Path dir : browserDirs()) {
                if (hasBrowser(dir, "firefox-") && tryLaunch(dir, Playwright::firefox, null)) {
                    return "Playwright Firefox" + location(dir);
                }
            }
        }
        if (!wantFirefox) {
            for (Path dir : browserDirs()) {
                if (hasBrowser(dir, "chromium") && tryLaunch(dir, Playwright::chromium, null)) {
                    return "Playwright Chromium" + location(dir);
                }
            }
        }
        return null;
    }

    /**
     * Tries to launch one browser headlessly.
     *
     * @param browsersDir the Playwright browser folder to look in.
     * @param type        selects Firefox or Chromium.
     * @param executable  a system browser binary, or {@code null} to use Playwright's own build.
     * @return {@code true} if the browser started; otherwise everything is cleaned up again.
     */
    private boolean tryLaunch(Path browsersDir, Function<Playwright, BrowserType> type, Path executable) {
        try {
            initPlaywright(browsersDir);
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (executable != null) options.setExecutablePath(executable);
            browser = type.apply(playwright).launch(options);
            return true;
        } catch (Exception e) {
            cleanupPlaywright();
            return false;
        }
    }

    /**
     * Describes where a browser was found, for the "using ..." message.
     *
     * @param dir the browser folder a browser was launched from.
     * @return the description to append; {@code ""} for WebGrep's own folder.
     */
    private static String location(Path dir) {
        return dir.equals(sharedBrowsersPath()) ? " (shared Playwright cache)" : "";
    }

    /**
     * Interactively prompts the user to choose between Firefox and Chromium for download.
     * Called only when no browser is found and a TTY is available.
     *
     * @return {@code "firefox"} (default, if user presses Enter) or {@code "chromium"}.
     */
    private String promptBrowserChoice() {
        System.err.println();
        System.err.println("  SPA detected but no compatible browser is available.");
        System.err.println("  Choose a browser for WebGrep to download (one-time):");
        System.err.println("    [1] Firefox  (Mozilla Foundation, ~105 MB)  [default]");
        System.err.println("    [2] Chromium (Google, ~120 MB)");
        System.err.println();
        System.err.print("  Enter choice [1/2] or press Enter for Firefox: ");

        try {
            java.io.Console console = System.console();
            if (console != null) {
                String line = console.readLine();
                if (line != null && line.trim().equals("2")) return "chromium";
            }
        } catch (Exception ignored) {}
        return "firefox";
    }

    /**
     * Downloads a Playwright browser into WebGrep's own browser folder.
     *
     * <p>Runs the official Playwright installer ({@code com.microsoft.playwright.CLI install})
     * in a subprocess. Calling it in-process would end the JVM: the installer finishes with
     * {@code System.exit()}, which would terminate a running crawl. The installer's output is
     * shown to the user.
     *
     * @param browserType {@code "firefox"} or {@code "chromium"}.
     * @return the installer's exit code; {@code 0} on success.
     * @throws IOException          if the installer cannot be started.
     * @throws InterruptedException if interrupted while waiting for it.
     */
    public static int installBrowser(String browserType) throws IOException, InterruptedException {
        String javaExe = ProcessHandle.current().info().command().orElse("java");
        ProcessBuilder installer = new ProcessBuilder(javaExe, "-cp", playwrightClasspath(),
                "com.microsoft.playwright.CLI", "install",
                "chromium".equalsIgnoreCase(browserType) ? "chromium" : "firefox")
                .inheritIO();
        installer.environment().put("PLAYWRIGHT_BROWSERS_PATH", browsersPath().toString());
        return installer.start().waitFor();
    }

    /**
     * Returns a description of the browser WebGrep would use, without downloading anything.
     *
     * <p>Each candidate is actually launched and closed again, so "available" means "starts",
     * not merely "a folder of that name exists".
     *
     * @param timeoutMs        navigation timeout; unused for the check itself.
     * @param preferredBrowser {@code "auto"}, {@code "firefox"} or {@code "chromium"}.
     * @return the description, e.g. {@code "Playwright Firefox"}; or {@code null} if no browser
     *         starts.
     */
    public static String findUsableBrowser(int timeoutMs, String preferredBrowser) {
        try (PlaywrightRenderer probe = new PlaywrightRenderer(timeoutMs, false, preferredBrowser)) {
            return probe.launchInstalled();
        }
    }

    /**
     * Releases all Playwright resources ({@link #playwright} and {@link #browser}) without
     * throwing. Used to clean up after a failed browser launch attempt before falling through
     * to the next resolution tier.
     */
    private void cleanupPlaywright() {
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
        playwright = null;
        browser = null;
    }

    /**
     * Creates a new {@link Playwright} instance that looks for browsers in {@code browsersDir}.
     *
     * <p>{@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} prevents Playwright from downloading a browser
     * on its own during initialisation; downloads only ever happen through
     * {@link #installBrowser}, after the user has agreed.
     *
     * <p>Playwright emits noisy initialisation output to stderr; that stream is temporarily
     * redirected to null while the instance is created, then restored immediately after.
     *
     * @param browsersDir the Playwright browser folder to use.
     */
    private void initPlaywright(Path browsersDir) {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        env.put("PLAYWRIGHT_BROWSERS_PATH", browsersDir.toString());
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        } finally {
            System.setErr(originalErr);
        }
    }

    /**
     * Returns WebGrep's own browser folder, where browsers it downloads are installed.
     *
     * <p>If {@code PLAYWRIGHT_BROWSERS_PATH} is set, that folder is used instead, for both
     * looking up and installing browsers. Otherwise:
     * <ul>
     *   <li>Linux: {@code $XDG_CACHE_HOME/webgrep/browsers} ({@code ~/.cache/webgrep/browsers})</li>
     *   <li>macOS: {@code ~/Library/Caches/webgrep/browsers}</li>
     *   <li>Windows: {@code %LOCALAPPDATA%\webgrep\browsers}</li>
     * </ul>
     *
     * @return the folder; it need not exist yet.
     */
    public static Path browsersPath() {
        String env = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (env != null && !env.isBlank()) return Paths.get(env);
        return cacheRoot().resolve("webgrep").resolve("browsers");
    }

    /**
     * Returns the cache folder Playwright shares between all programs that use it.
     *
     * @return {@code ms-playwright} inside the platform's cache directory.
     */
    private static Path sharedBrowsersPath() {
        return cacheRoot().resolve("ms-playwright");
    }

    /**
     * Returns the folders to look for Playwright browsers in.
     *
     * @return WebGrep's own folder, then the shared cache; with {@code PLAYWRIGHT_BROWSERS_PATH}
     *         set, only that folder.
     */
    static List<Path> browserDirs() {
        String env = System.getenv("PLAYWRIGHT_BROWSERS_PATH");
        if (env != null && !env.isBlank()) return List.of(Paths.get(env));
        return List.of(browsersPath(), sharedBrowsersPath());
    }

    /**
     * Returns the platform's per-user cache directory, as Playwright itself determines it.
     *
     * @return {@code %LOCALAPPDATA%} on Windows, {@code ~/Library/Caches} on macOS, and
     *         {@code $XDG_CACHE_HOME} (default {@code ~/.cache}) elsewhere.
     */
    private static Path cacheRoot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");
        if (os.contains("win")) {
            return Paths.get(System.getenv().getOrDefault("LOCALAPPDATA", home + "\\AppData\\Local"));
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Caches");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        return xdg != null && !xdg.isBlank() ? Paths.get(xdg) : Paths.get(home, ".cache");
    }

    /**
     * Returns the classpath entry (JAR path or classes directory) that contains the Playwright
     * runtime, for use when spawning a subprocess to run {@code com.microsoft.playwright.CLI}.
     *
     * <p>Uses {@link URI} conversion to correctly handle directory paths that contain spaces,
     * unlike the URL-encoded string returned by {@link java.net.URL#getPath()}.
     *
     * @return the file-system path of the JAR or classes directory.
     */
    public static String playwrightClasspath() {
        try {
            URI loc = PlaywrightRenderer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            return Paths.get(loc).toString();
        } catch (Exception e) {
            return PlaywrightRenderer.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
        }
    }

    /**
     * Returns {@code true} if {@code dir} contains a browser build whose folder name starts with
     * {@code prefix} (e.g. {@code "firefox-"}).
     *
     * @param dir    a Playwright browser folder.
     * @param prefix the folder name prefix to look for.
     * @return {@code true} if at least one matching folder exists; {@code false} on any error.
     */
    private static boolean hasBrowser(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) return false;
        try (var entries = Files.list(dir)) {
            return entries.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Gives the crawler's cookies to a new browser context before its first navigation.
     *
     * <p>Each cookie keeps its attributes, so the browser sends it only where it belongs. A
     * cookie the browser refuses (for instance an invalid name) is skipped on its own rather
     * than failing the page.
     *
     * @param context the browser context.
     * @param cookies the cookies to add.
     */
    private static void passCookies(BrowserContext context, List<CookieJar.Cookie> cookies) {
        if (cookies.isEmpty()) return;
        List<Cookie> converted = cookies.stream().map(PlaywrightRenderer::toBrowserCookie).toList();
        try {
            context.addCookies(converted);
        } catch (Exception batchFailed) {
            for (Cookie cookie : converted) {
                try {
                    context.addCookies(List.of(cookie));
                } catch (Exception ignored) {
                    // The page still loads without this cookie.
                }
            }
        }
    }

    /**
     * Converts a jar cookie to Playwright's form. A leading dot on the domain is how the browser
     * marks a cookie that subdomains receive too; without it the cookie is host-only.
     *
     * @param c the jar cookie.
     * @return the same cookie for the browser.
     */
    static Cookie toBrowserCookie(CookieJar.Cookie c) {
        Cookie cookie = new Cookie(c.name(), c.value())
                .setDomain(c.hostOnly() ? c.domain() : "." + c.domain())
                .setPath(c.path())
                .setSecure(c.secure())
                .setHttpOnly(c.httpOnly());
        if (c.expiresAt() >= 0) cookie.setExpires(c.expiresAt() / 1000.0);
        return cookie;
    }

    /**
     * Converts a cookie reported by the browser to the jar's form.
     *
     * @param c the browser cookie; its domain must not be {@code null}.
     * @return the same cookie for the jar.
     */
    static CookieJar.Cookie fromBrowserCookie(Cookie c) {
        String domain = c.domain.toLowerCase(Locale.ROOT);
        boolean hostOnly = !domain.startsWith(".");
        if (!hostOnly) domain = domain.substring(1);
        long expiresAt = c.expires == null || c.expires < 0 ? -1 : (long) (c.expires * 1000);
        return new CookieJar.Cookie(c.name, c.value, domain, hostOnly,
                c.path == null || c.path.isEmpty() ? "/" : c.path,
                Boolean.TRUE.equals(c.secure), Boolean.TRUE.equals(c.httpOnly), expiresAt);
    }

    /**
     * Extracts the scheme+host (base URL) from a full URL string.
     *
     * <p>Used to determine whether a new navigation requires a fresh browser context
     * (different domain) or can reuse the existing one (same domain).
     *
     * <p>A non-default port is included, both so that two services on the same host are given
     * separate browser contexts and so that root-relative document URLs found in intercepted
     * JSON responses are prefixed with the port they actually live on.
     *
     * @param url the full URL.
     * @return the scheme+host(+port) string, e.g. {@code "https://example.com"} or
     *         {@code "http://localhost:3000"}; or {@code ""} if the URL is malformed.
     */
    private static String baseUrl(String url) {
        try {
            URL u = new URL(url);
            int port = u.getPort();
            String portPart = (port != -1 && port != u.getDefaultPort()) ? ":" + port : "";
            return u.getProtocol() + "://" + u.getHost() + portPart;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Closes all Playwright resources: the persistent context, the browser, and the Playwright
     * instance. Called automatically when the crawler is done (via {@code try-with-resources}
     * or the {@code finally} block in {@link com.webgrep.core.Crawler#crawl()}).
     */
    @Override
    public void close() {
        closePersistentContext();
        if (browser != null) try { browser.close(); } catch (Exception ignored) {}
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
        browser = null;
        playwright = null;
    }
}
