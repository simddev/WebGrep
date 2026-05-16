package com.webgrep.core;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
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
 * <p>Browser resolution order (first available wins):
 * <ol>
 *   <li>System Chromium/Chrome — drives natively via CDP, most reliable</li>
 *   <li>System Firefox — best-effort; may fail on Dev/Nightly editions</li>
 *   <li>Playwright's cached Firefox (~/.cache/ms-playwright)</li>
 *   <li>Playwright's cached Chromium</li>
 *   <li>Prompt user to download Firefox (default) or Chromium</li>
 * </ol>
 * Use {@code --browser firefox|chromium} to skip to the preferred tier.
 */
public class PlaywrightRenderer implements AutoCloseable {

    // Matches relative or absolute URLs ending in a document extension inside JSON strings.
    private static final Pattern JSON_DOC_URL = Pattern.compile(
            "\"((?:https?://[^\"]+|/[^\"\\s]+)\\.(?:pdf|docx|xlsx|odt|doc|pptx|csv)(?:\\?[^\"]*)?)\"",
            Pattern.CASE_INSENSITIVE);
    // Matches REST download endpoints where the terminal path segment is literally "download".
    // Catches common API patterns like /api/files/123/download or /soubor/456/download.
    private static final Pattern JSON_DOWNLOAD_URL = Pattern.compile(
            "\"((?:https?://[^\"\\s\"]{4,}|/[^\"\\s\"]{4,})/download)\"",
            Pattern.CASE_INSENSITIVE);

    private Playwright playwright;
    private Browser browser;
    private BrowserContext persistentContext;
    private Page persistentPage;
    private String persistentBaseUrl;
    // Shared state for the persistent page's response interceptor; cleared before each navigation.
    private final List<String> interceptedLinks = Collections.synchronizedList(new ArrayList<>());
    private volatile String interceptorUrlBase = "";
    private boolean unavailable = false;
    private final int timeoutMs;
    private final boolean insecure;
    private final String preferredBrowser; // null/"auto", "firefox", or "chromium"

    public record RenderedPage(String text, List<String> links, List<String> docLinks, Map<String, String> cookies) {}

    public PlaywrightRenderer(int timeoutMs, boolean insecure, String preferredBrowser) {
        this.timeoutMs = timeoutMs;
        this.insecure = insecure;
        this.preferredBrowser = "auto".equalsIgnoreCase(preferredBrowser) ? null : preferredBrowser;
    }

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
        // Slightly longer body (< 300 chars) requires 2+ bundles — SPAs always load multiple
        // chunks, whereas a static page with a single analytics script has only one.
        var scripts = doc.select("script[src*='.js']");
        return bodyText.length() < 100 && !scripts.isEmpty()
                || bodyText.length() < 300 && scripts.size() >= 2;
    }

    public RenderedPage render(String url, Map<String, String> cookies) {
        if (unavailable) return null;
        try {
            ensureReady();
        } catch (Exception e) {
            unavailable = true;
            String cause = e.getMessage() != null
                    ? e.getMessage().lines().findFirst().orElse("unknown error")
                    : e.getClass().getSimpleName();
            System.err.printf("%n  Warning: SPA rendering unavailable (%s).%n"
                    + "  JavaScript-rendered pages will return no results.%n%n", cause);
            return null;
        }
        try {
            String urlBase = baseUrl(url);

            // Keep a single BrowserContext and Page alive across renders on the same domain.
            // For SPA sub-routes, this means the JavaScript bundle is already loaded and the
            // router just swaps components — typically 1–3 s instead of 10–15 s per page.
            boolean needsNewPage = persistentPage == null || persistentPage.isClosed()
                    || !urlBase.equals(persistentBaseUrl);
            if (needsNewPage) {
                closePersistentContext();
                Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                        .setIgnoreHTTPSErrors(insecure);
                persistentContext = browser.newContext(ctxOpts);
                // Block images, fonts, stylesheets and media — they add no text content and
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
                passCookies(persistentContext, cookies, url);
                persistentBaseUrl = urlBase;
            }

            interceptedLinks.clear();
            interceptorUrlBase = urlBase;

            try {
                persistentPage.navigate(url);
            } catch (TimeoutError e) {
                // Navigation itself timed out — return null for this page but keep the
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
                // Wait until the content area has non-trivial text — resolves as soon as the
                // route renders (typically 150–400 ms) instead of the full NETWORKIDLE tail.
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

            // Single evaluate round-trip: extract both innerText and resolved hrefs together.
            // Previous approach called querySelectorAll then getAttribute per element — one
            // IPC call per anchor (80+ on some pages), adding ~150ms and leaking ElementHandles.
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = (Map<String, Object>) persistentPage.evaluate(
                    "(base) => {"
                    + "  const ls = [], ds = [];"
                    + "  for (const a of document.querySelectorAll('a[href]')) {"
                    + "    const h = a.getAttribute('href');"
                    + "    if (!h || h.startsWith('javascript:') || h.startsWith('mailto:')"
                    + "        || h.startsWith('#')) continue;"
                    + "    try {"
                    + "      const u = new URL(h, base).toString();"
                    + "      ls.push(u);"
                    // Links marked with the HTML5 download attribute are file downloads, not navigation.
                    + "      if (a.hasAttribute('download')) ds.push(u);"
                    + "    } catch(_) {}"
                    + "  }"
                    + "  const wgRoot = document.querySelector("
                    + "    'main, [role=main], [role=content], app-root, #app, #root, #content'"
                    + "  ) || document.body;"
                    + "  const wgClone = wgRoot ? wgRoot.cloneNode(true) : null;"
                    + "  if (wgClone) wgClone.querySelectorAll('script,style,noscript').forEach(n=>n.remove());"
                    + "  return { text: wgClone ? (wgClone.textContent || '') : '',"
                    + "           links: [...new Set(ls)], downloadLinks: [...new Set(ds)] };"
                    + "}", url);

            String text = snapshot != null ? (String) snapshot.get("text") : "";
            @SuppressWarnings("unchecked")
            List<String> domLinks = snapshot != null
                    ? (List<String>) snapshot.get("links")
                    : new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<String> domDownloadLinks = snapshot != null && snapshot.get("downloadLinks") != null
                    ? (List<String>) snapshot.get("downloadLinks")
                    : new ArrayList<>();

            List<String> captured = new ArrayList<>(interceptedLinks);
            // Add explicitly-marked download links so they bypass the depth limit in the crawler
            captured.addAll(domDownloadLinks);

            // Round-trip cookies for the crawled host back to the crawler's jar so they
            // survive context switches and are available to the Jsoup fetcher.
            // Only include cookies scoped to the page's own domain — third-party cookies
            // set by analytics scripts must not be filed under the wrong host.
            Map<String, String> contextCookies = new HashMap<>();
            if (persistentContext != null) {
                try {
                    String pageHost = new URL(url).getHost().toLowerCase();
                    persistentContext.cookies().forEach(c -> {
                        if (c.name == null || c.value == null) return;
                        if (c.domain == null) { contextCookies.put(c.name, c.value); return; }
                        String d = c.domain.toLowerCase();
                        if (d.startsWith(".")) d = d.substring(1);
                        if (pageHost.equals(d) || pageHost.endsWith("." + d)) {
                            contextCookies.put(c.name, c.value);
                        }
                    });
                } catch (Exception ignored) {}
            }

            return new RenderedPage(
                    text != null ? text : "",
                    domLinks != null ? domLinks : new ArrayList<>(),
                    captured.stream().distinct().collect(Collectors.toList()),
                    contextCookies);
        } catch (Exception e) {
            // Page-level error (JS exception, stale context, etc.) — skip this page but
            // keep the renderer alive so subsequent pages can still be rendered.
            String cause = e.getMessage() != null
                    ? e.getMessage().lines().findFirst().orElse("unknown error")
                    : e.getClass().getSimpleName();
            System.err.printf("%n  Warning: SPA render failed for %s (%s). Skipping page.%n%n",
                    url, cause);
            closePersistentContext();
            return null;
        }
    }

    private void closePersistentContext() {
        if (persistentContext != null) {
            try { persistentContext.close(); } catch (Exception ignored) {}
            persistentContext = null;
            persistentPage = null;
        }
        persistentBaseUrl = null;
    }

    /**
     * Called for every HTTP response the browser receives. Regex-scans any JSON
     * API response for URL strings ending in common document extensions
     * (.pdf, .docx, .xlsx, …) and adds them to the intercepted-links list so the
     * crawler can fetch and search those documents.
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

    private void ensureReady() throws IOException, InterruptedException {
        if (browser != null && browser.isConnected()) return;

        // Close stale resources before reinitializing (e.g. after browser disconnect).
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
        playwright = null;
        browser = null;

        boolean wantChromium = "chromium".equalsIgnoreCase(preferredBrowser);
        boolean wantFirefox  = "firefox".equalsIgnoreCase(preferredBrowser);

        // ── 1. System Chromium / Chrome ───────────────────────────────────────
        // Chromium-family browsers speak CDP natively — Playwright drives them via
        // executablePath without any custom patches, so this is the most reliable option.
        if (!wantFirefox) {
            Optional<Path> sysChr = BrowserFinder.findChromium();
            if (sysChr.isPresent()) {
                try {
                    initPlaywright();
                    browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                            .setExecutablePath(sysChr.get()).setHeadless(true));
                    System.err.printf("  SPA renderer: using system Chromium (%s)%n", sysChr.get());
                    return;
                } catch (Exception e) {
                    cleanupPlaywright();
                }
            }
        }

        // ── 2. System Firefox ─────────────────────────────────────────────────
        // Best-effort: Playwright needs its patched Firefox, so this can fail on Dev/Nightly.
        if (!wantChromium) {
            Optional<Path> sysFf = BrowserFinder.findFirefox();
            if (sysFf.isPresent()) {
                try {
                    initPlaywright();
                    browser = playwright.firefox().launch(new BrowserType.LaunchOptions()
                            .setExecutablePath(sysFf.get()).setHeadless(true));
                    System.err.printf("  SPA renderer: using system Firefox (%s)%n", sysFf.get());
                    return;
                } catch (Exception e) {
                    // Dev/Nightly often incompatible with Playwright's protocol — fall through silently.
                    cleanupPlaywright();
                }
            }
        }

        // ── 3. Playwright's cached Firefox ────────────────────────────────────
        if (!wantChromium && isFirefoxCached()) {
            System.err.printf("  SPA renderer: using Playwright Firefox%n");
            initPlaywright();
            browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
            return;
        }

        // ── 4. Playwright's cached Chromium ───────────────────────────────────
        if (!wantFirefox && isChromiumCached()) {
            System.err.printf("  SPA renderer: using Playwright Chromium%n");
            initPlaywright();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            return;
        }

        // ── 5. Nothing found — ask user which browser to download ─────────────
        // Refuse to download silently in non-interactive sessions (CI, piped output,
        // Docker). Without a TTY the user cannot confirm the download; they must run
        // 'webgrep --install-browser' explicitly first.
        if (System.console() == null) {
            throw new IOException("No browser available for SPA rendering and no TTY to prompt. "
                    + "Run 'webgrep --install-browser' to install one.");
        }
        String chosen = (preferredBrowser != null) ? preferredBrowser : promptBrowserChoice();
        downloadAndLaunch(chosen);
    }

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

    private void downloadAndLaunch(String browserType) throws IOException, InterruptedException {
        boolean isChromium = "chromium".equalsIgnoreCase(browserType);
        String label = isChromium ? "Chromium (Google)" : "Firefox (Mozilla)";
        System.err.printf("%n  Downloading Playwright %s for SPA rendering (~%s MB, one-time)...%n",
                label, isChromium ? "120" : "105");
        com.microsoft.playwright.CLI.main(new String[]{"install", isChromium ? "chromium" : "firefox"});
        System.err.println();

        initPlaywright();
        if (isChromium) {
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } else {
            browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
    }

    private void cleanupPlaywright() {
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
        playwright = null;
        browser = null;
    }

    private void initPlaywright() throws IOException {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        } finally {
            System.setErr(originalErr);
        }
    }

    private static boolean isFirefoxCached() {
        return isPlaywrightCached("firefox-");
    }

    private static boolean isChromiumCached() {
        return isPlaywrightCached("chromium-");
    }

    private static boolean isPlaywrightCached(String prefix) {
        try {
            Path cache = Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
            if (!Files.isDirectory(cache)) return false;
            try (var entries = Files.list(cache)) {
                return entries.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
            }
        } catch (IOException e) {
            return false;
        }
    }

    private void passCookies(BrowserContext context, Map<String, String> cookies, String url) {
        if (cookies.isEmpty()) return;
        try {
            String domain = new URL(url).getHost().toLowerCase();
            List<Cookie> pwCookies = cookies.entrySet().stream()
                    .map(e -> new Cookie(e.getKey(), e.getValue()).setDomain(domain).setPath("/"))
                    .collect(Collectors.toList());
            context.addCookies(pwCookies);
        } catch (MalformedURLException ignored) {}
    }

    private static String baseUrl(String url) {
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (MalformedURLException e) {
            return "";
        }
    }

    @Override
    public void close() {
        closePersistentContext();
        if (browser != null) try { browser.close(); } catch (Exception ignored) {}
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
    }
}
