package com.webgrep.core;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import org.jsoup.nodes.Document;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Falls back to a headless Chromium browser for pages that are Angular/React/Vue SPAs —
 * i.e. pages where Jsoup gets only an empty JS shell with no crawlable content.
 *
 * <p>Detection is automatic: {@link #isSpa(Document)} checks for well-known SPA markers
 * and text-sparsity heuristics. The browser process is created lazily on the first SPA
 * page and reused for the rest of the crawl; it is closed when the caller closes this object.
 *
 * <p>On first use, if Chromium is not installed, it is downloaded automatically (~150 MB,
 * one-time). Subsequent runs use the cached binary.
 */
public class PlaywrightRenderer implements AutoCloseable {

    private Playwright playwright;
    private Browser browser;
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
     * Renders {@code url} in a headless browser and returns the visible text and all href links.
     * Returns {@code null} if rendering fails; the caller should fall back to Jsoup content.
     */
    public RenderedPage render(String url, Map<String, String> cookies) {
        try {
            ensureReady();
            Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                    .setIgnoreHTTPSErrors(insecure);
            try (BrowserContext context = browser.newContext(ctxOpts)) {
                passCoookies(context, cookies, url);
                Page page = context.newPage();
                page.setDefaultTimeout(timeoutMs);
                page.navigate(url);
                try {
                    page.waitForLoadState(LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(Math.min(timeoutMs, 15_000)));
                } catch (TimeoutError ignored) {
                    // Some SPAs keep polling; DOM content is already loaded at this point.
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
            return null;
        }
    }

    private void ensureReady() {
        if (browser != null && browser.isConnected()) return;
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        } catch (PlaywrightException e) {
            if (e.getMessage() != null && e.getMessage().contains("Executable doesn't exist")) {
                System.err.printf("%n  Downloading Chromium for SPA rendering (one-time, ~150 MB)...%n");
                try {
                    com.microsoft.playwright.CLI.main(new String[]{"install", "chromium"});
                } catch (Exception install) {
                    throw new RuntimeException("Chromium install failed", install);
                }
                playwright = Playwright.create();
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            } else {
                throw e;
            }
        }
    }

    private void passCoookies(BrowserContext context, Map<String, String> cookies, String url) {
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
