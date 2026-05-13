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
import java.util.stream.Collectors;

/**
 * Falls back to a headless Firefox browser for pages that are Angular/React/Vue SPAs —
 * i.e. pages where Jsoup gets only an empty JS shell with no crawlable content.
 *
 * <p>Uses Playwright's own Firefox build (Mozilla Firefox, cached in ~/.cache/ms-playwright).
 * On first use, Firefox is downloaded automatically (~105 MB, one-time). No Google products
 * or system-level browser installation required.
 *
 * <p>The browser process is created lazily on the first SPA page and reused for the rest
 * of the crawl; it is closed when the caller closes this object. If anything goes wrong,
 * a one-time warning is printed and the renderer marks itself unavailable — subsequent
 * SPA pages fall back to Jsoup content.
 */
public class PlaywrightRenderer implements AutoCloseable {

    private Playwright playwright;
    private Browser browser;
    private boolean unavailable = false;
    private final int timeoutMs;
    private final boolean insecure;

    public record RenderedPage(String text, List<String> links) {}

    public PlaywrightRenderer(int timeoutMs, boolean insecure) {
        this.timeoutMs = timeoutMs;
        this.insecure = insecure;
    }

    /**
     * Returns true when the Jsoup-parsed document looks like a client-side rendered SPA shell
     * rather than server-rendered HTML with actual content.
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
        // Sparse body + bundled JS scripts → likely a SPA shell
        String bodyText = doc.body() != null ? doc.body().text() : "";
        return bodyText.length() < 300 && !doc.select("script[src*='.js']").isEmpty();
    }

    /**
     * Renders {@code url} in a headless Firefox browser and returns the visible text and all href links.
     * Returns {@code null} if rendering fails or the renderer is unavailable.
     */
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
                page.navigate(url);
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 15_000)));
                } catch (TimeoutError ignored) {
                    // Some SPAs keep background polling; DOM content is already loaded.
                }
                String text = (String) page.evaluate("document.body ? document.body.innerText : ''");
                List<String> links = page.querySelectorAll("a[href]").stream()
                        .map(el -> el.getAttribute("href"))
                        .filter(href -> href != null && !href.isBlank())
                        .map(href -> resolveLink(href, url))
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                return new RenderedPage(text != null ? text : "", links);
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

    private void ensureReady() throws IOException, InterruptedException {
        if (browser != null && browser.isConnected()) return;

        if (!isFirefoxCached()) {
            System.err.printf("%n  Downloading Firefox for SPA rendering (one-time, ~105 MB)...%n");
            com.microsoft.playwright.CLI.main(new String[]{"install", "firefox"});
            System.err.println();
        }

        // PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 prevents Playwright.create() from re-validating
        // and printing "BEWARE: OS not officially supported" on every run.
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        } finally {
            System.setErr(originalErr);
        }

        browser = playwright.firefox().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    private static boolean isFirefoxCached() {
        try {
            Path cache = Paths.get(System.getProperty("user.home"), ".cache", "ms-playwright");
            if (!Files.isDirectory(cache)) return false;
            try (var entries = Files.list(cache)) {
                return entries.anyMatch(p -> p.getFileName().toString().startsWith("firefox-"));
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
