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
 * Falls back to a headless Firefox browser for pages that are Angular/React/Vue SPAs.
 *
 * <p>Beyond rendering page text, it intercepts JSON API responses that the SPA makes
 * during load and extracts document download URLs that are not present as plain
 * {@code <a href>} links in the DOM (e.g. click-handler-driven PDF downloads).
 *
 * <p>Uses Playwright's own Firefox build (Mozilla). On first SPA encounter, Firefox is
 * downloaded once (~105 MB) to {@code ~/.cache/ms-playwright}. No Google products or
 * system-level browser installation required.
 */
public class PlaywrightRenderer implements AutoCloseable {

    // Matches any relative or absolute URL ending in a document extension inside JSON strings.
    private static final Pattern JSON_DOC_URL = Pattern.compile(
            "\"((?:https?://[^\"]+|/[^\"\\s]+)\\.(?:pdf|docx|xlsx|odt|doc|pptx|zip|csv))\"",
            Pattern.CASE_INSENSITIVE);

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

                page.navigate(url);
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

                List<String> allLinks = new ArrayList<>(domLinks);
                allLinks.addAll(interceptedLinks);
                return new RenderedPage(text != null ? text : "",
                        allLinks.stream().distinct().collect(Collectors.toList()));
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

        if (!isFirefoxCached()) {
            System.err.printf("%n  Downloading Firefox for SPA rendering (one-time, ~105 MB)...%n");
            com.microsoft.playwright.CLI.main(new String[]{"install", "firefox"});
            System.err.println();
        }

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
