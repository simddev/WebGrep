package com.webgrep.core;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import org.json.JSONArray;
import org.json.JSONObject;
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

    // Matches any relative or absolute URL ending in a document extension inside JSON strings.
    // .zip excluded: isIgnoredLink() filters zip from non-SPA crawls; keeping it here
    // would inconsistently queue zip files only on SPA pages.
    private static final Pattern JSON_DOC_URL = Pattern.compile(
            "\"((?:https?://[^\"]+|/[^\"\\s]+)\\.(?:pdf|docx|xlsx|odt|doc|pptx|csv))\"",
            Pattern.CASE_INSENSITIVE);

    private Playwright playwright;
    private Browser browser;
    private boolean unavailable = false;
    private final int timeoutMs;
    private final boolean insecure;
    private final String preferredBrowser; // null/"auto", "firefox", or "chromium"

    public record RenderedPage(String text, List<String> links, List<String> docLinks) {}

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
        return bodyText.length() < 300 && !doc.select("script[src*='.js']").isEmpty();
    }

    public RenderedPage render(String url, Map<String, String> cookies) {
        if (unavailable) return null;
        try {
            ensureReady();
            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(insecure);
            try (BrowserContext context = browser.newContext(ctxOpts)) {
                passCookies(context, cookies, url);
                Page page = context.newPage();
                page.setDefaultTimeout(timeoutMs);

                // Intercept JSON API responses to capture document download URLs that
                // the SPA serves via click handlers rather than plain <a href> links.
                List<String> interceptedLinks = Collections.synchronizedList(new ArrayList<>());
                String urlBase = baseUrl(url);
                page.onResponse(response -> captureDocLinks(response, urlBase, interceptedLinks));

                try {
                    page.navigate(url);
                } catch (TimeoutError e) {
                    // Navigation itself timed out — return null for this page but keep the
                    // renderer alive; a slow page shouldn't disable SPA rendering entirely.
                    return null;
                }
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 15_000)));
                } catch (TimeoutError ignored) {}

                String text = (String) page.evaluate("document.body ? document.body.innerText : ''");

                // Standard <a href> links from the rendered DOM
                List<String> domLinks = page.querySelectorAll("a[href]").stream()
                        .map(el -> el.getAttribute("href"))
                        .filter(href -> href != null && !href.isBlank())
                        .map(href -> resolveLink(href, url))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                return new RenderedPage(
                        text != null ? text : "",
                        domLinks.stream().distinct().collect(Collectors.toList()),
                        interceptedLinks.stream().distinct().collect(Collectors.toList()));
            }
        } catch (Exception e) {
            unavailable = true;
            String cause = e.getMessage() != null
                    ? e.getMessage().lines().findFirst().orElse("unknown error")
                    : e.getClass().getSimpleName();
            System.err.printf("%n  Warning: SPA rendering unavailable (%s).%n"
                    + "  JavaScript-rendered pages will return no results.%n%n", cause);
            return null;
        }
    }

    /**
     * Called for every HTTP response the browser receives. Extracts document download
     * URLs from JSON API responses in two ways:
     * <ol>
     *   <li>infodeska.gov.cz {@code vyveseni/vyhledej}: parses the notices array and
     *       constructs {@code /soubor/{id}/download} URLs from each file's UUID.</li>
     *   <li>Generic: regex-scans any JSON response for URL strings ending in common
     *       document extensions (.pdf, .docx, .xlsx, …).</li>
     * </ol>
     */
    private static void captureDocLinks(Response response, String urlBase, List<String> out) {
        try {
            if (response.status() != 200) return;
            String ct = response.headers().getOrDefault("content-type", "");
            if (!ct.contains("application/json")) return;
            String body = response.text();
            String respUrl = response.url();

            if (respUrl.contains("/vyveseni/vyhledej")) {
                extractInfodeskaLinks(body, urlBase, out);
                return;
            }

            // Generic fallback: extract document URLs embedded as strings in any JSON response.
            Matcher m = JSON_DOC_URL.matcher(body);
            while (m.find()) {
                String found = m.group(1);
                if (found.startsWith("/")) {
                    out.add(urlBase + found);
                } else {
                    out.add(found);
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Parses the infodeska.gov.cz {@code vyveseni/vyhledej} response.
     * Each notice in the array can have a {@code soubory} list whose items carry
     * an {@code id} (UUID). The download URL is {@code /eudpub/api/v1/vyveseni/soubor/{id}/download}.
     */
    private static void extractInfodeskaLinks(String body, String urlBase, List<String> out) {
        // Derive the API base from urlBase (drop any path prefix beyond the host)
        String apiBase = urlBase + "/eudpub/api/v1/vyveseni";
        JSONArray array = new JSONArray(body);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            JSONArray soubory = item.optJSONArray("soubory");
            if (soubory == null) continue;
            for (int j = 0; j < soubory.length(); j++) {
                String id = soubory.optJSONObject(j) != null
                        ? soubory.getJSONObject(j).optString("id", "")
                        : "";
                if (!id.isEmpty()) {
                    out.add(apiBase + "/soubor/" + id + "/download");
                }
            }
        }
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
            String domain = new URL(url).getHost();
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

    private String resolveLink(String href, String baseUrl) {
        if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("#"))
            return null;
        try {
            return new URL(new URL(baseUrl), href).toString();
        } catch (MalformedURLException e) {
            return null;
        }
    }

    @Override
    public void close() {
        if (browser != null) try { browser.close(); } catch (Exception ignored) {}
        if (playwright != null) try { playwright.close(); } catch (Exception ignored) {}
    }
}
