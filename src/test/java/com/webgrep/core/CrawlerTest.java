package com.webgrep.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.webgrep.config.CliOptions;
import com.webgrep.reporting.CrawlResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link Crawler}: meta-refresh parsing, and crawl behaviour against a real local
 * HTTP server - redirects, cookies, depth, the page budget and duplicate detection.
 *
 * <p>The server answers on the loopback address under two host names, {@code localhost} and
 * {@code 127.0.0.1}, which the crawler treats as two different sites.
 */
public class CrawlerTest {

    /** A canned response: status, headers, body. */
    private record Reply(int status, Map<String, List<String>> headers, String body) {
        static Reply html(String body, String... headerPairs) {
            Map<String, List<String>> h = new HashMap<>();
            h.put("Content-Type", List.of("text/html; charset=utf-8"));
            for (int i = 0; i < headerPairs.length; i += 2) {
                h.computeIfAbsent(headerPairs[i], k -> new ArrayList<>()).add(headerPairs[i + 1]);
            }
            return new Reply(200, h, "<html><head><title>t</title></head><body>" + body + "</body></html>");
        }

        static Reply redirect(String location) {
            return new Reply(302, Map.of("Location", List.of(location)), "");
        }

        static Reply status(int code) {
            return new Reply(code, Map.of("Content-Type", List.of("text/html")), "<html><body>error</body></html>");
        }
    }

    /** One request the server received. */
    private record Request(String host, String path, String cookie) {}

    private HttpServer server;
    private int port;
    private final Map<String, Reply> routes = new HashMap<>();
    /** Replies served in order for a path before falling back to {@link #routes}. */
    private final Map<String, Deque<Reply>> sequences = new HashMap<>();
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String host = exchange.getRequestHeaders().getFirst("Host");
        host = host == null ? "" : host.replaceFirst(":\\d+$", "");
        String path = exchange.getRequestURI().toString();
        requests.add(new Request(host, path, exchange.getRequestHeaders().getFirst("Cookie")));
        Deque<Reply> sequence = sequences.get(path);
        Reply reply = sequence != null && !sequence.isEmpty() ? sequence.poll()
                : routes.getOrDefault(host + path, routes.getOrDefault(path, Reply.status(404)));
        reply.headers().forEach((k, v) -> v.forEach(value -> exchange.getResponseHeaders().add(k, value)));
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private String url(String host, String path) {
        return "http://" + host + ":" + port + path;
    }

    private CrawlResult crawl(String seed, String keyword, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("-u", seed, "-k", keyword, "--delay-ms", "0"));
        args.addAll(List.of(extraArgs));
        CliOptions options = CliOptions.parse(args.toArray(new String[0]));
        options.validate();
        return new Crawler(options, new ContentExtractor(1024 * 1024), new MatchEngine()).crawl();
    }

    // ── meta refresh parsing ─────────────────────────────────────────────────

    private static String refreshTarget(String html) {
        return Crawler.metaRefreshTarget(
                org.jsoup.Jsoup.parse(html, "https://example.com/docs/intro"),
                "https://example.com/docs/intro");
    }

    @Test
    public void testZeroDelayMetaRefreshIsFollowed() {
        assertEquals("https://example.com/docs/4.x/intro",
                refreshTarget("<html><head><meta http-equiv=\"refresh\""
                        + " content=\"0; url=/docs/4.x/intro\"></head></html>"));
        // No space, quoted target, and uppercase URL= must all parse.
        assertEquals("https://example.com/a",
                refreshTarget("<html><head><meta http-equiv=refresh content='0;url=/a'></head></html>"));
        assertEquals("https://example.com/b",
                refreshTarget("<html><head><meta http-equiv=refresh content=\"0; URL='/b'\"></head></html>"));
        assertEquals("https://other.org/c",
                refreshTarget("<html><head><meta http-equiv=refresh"
                        + " content=\"0; url=https://other.org/c\"></head></html>"));
    }

    @Test
    public void testDelayedOrUnusableMetaRefreshIsIgnored() {
        // A delayed refresh is a normal page that reloads itself, not a redirect.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"30; url=/home\"></head></html>"));
        // Refresh with no target, or no refresh at all.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh content=\"0\"></head></html>"));
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh content=\"0; url=\"></head></html>"));
        assertNull(refreshTarget("<html><head></head><body>plain</body></html>"));
        // Non-http schemes must not slip through as redirect targets.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"0; url=javascript:alert(1)\"></head></html>"));
        // A refresh pointing at the page itself would loop.
        assertNull(refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"0; url=https://example.com/docs/intro\"></head></html>"));
    }

    @Test
    public void testMetaRefreshTargetCannotCarryEscapeCharacters() {
        // The target comes straight from page markup; an ESC in it must not survive into the
        // URL that is queued, fetched and printed.
        String target = refreshTarget("<html><head><meta http-equiv=refresh"
                + " content=\"0; url=/x&#27;]0;title&#7;\"></head></html>");
        assertNotNull(target);
        assertFalse(target.chars().anyMatch(c -> c < 0x20));
        assertTrue(target, target.contains("%1B"));
    }

    // ── redirects and cookies ────────────────────────────────────────────────

    @Test
    public void testRedirectToAnotherSiteIsNotFollowedWithoutAllowExternal() {
        routes.put("localhost/r/", Reply.html("<a href=\"/r/go\">go</a>", "Set-Cookie", "SESSION=secret; Path=/"));
        routes.put("localhost/r/go", Reply.redirect(url("127.0.0.1", "/r/landing")));
        routes.put("127.0.0.1/r/landing", Reply.html("external zebra"));

        CrawlResult result = crawl(url("localhost", "/r/"), "zebra");

        assertTrue("Nothing may be requested from the other site",
                requests.stream().noneMatch(r -> r.host().equals("127.0.0.1")));
        assertEquals(0, result.getTotalMatches());
        assertEquals(url("127.0.0.1", "/r/landing"), result.offsiteRedirects.get(url("localhost", "/r/go")));
        assertEquals(1, (int) result.errorCounts.get(CrawlResult.ErrorType.SKIPPED_OFFSITE));
    }

    @Test
    public void testFollowedRedirectDoesNotCarryCookiesToAnotherHost() {
        routes.put("localhost/r/", Reply.html("<a href=\"/r/go\">go</a>", "Set-Cookie", "SESSION=secret; Path=/"));
        routes.put("localhost/r/go", Reply.redirect(url("127.0.0.1", "/r/landing")));
        routes.put("127.0.0.1/r/landing", Reply.html("external zebra"));

        CrawlResult result = crawl(url("localhost", "/r/"), "zebra", "--allow-external");

        assertEquals("The redirect is followed with --allow-external", 1, result.getTotalMatches());
        Request landing = requests.stream().filter(r -> r.path().equals("/r/landing")).findFirst().orElseThrow();
        assertNull("localhost's cookie must not be sent to 127.0.0.1", landing.cookie());
        Request go = requests.stream().filter(r -> r.path().equals("/r/go")).findFirst().orElseThrow();
        assertEquals("The cookie still goes back to the host that set it", "SESSION=secret", go.cookie());
    }

    @Test
    public void testCookieSetDuringRedirectIsKeptForItsOwnHost() {
        // The cookie arrives on the 302 itself; the next request to that host must carry it.
        routes.put("/c/", Reply.html("<a href=\"/c/login\">login</a> <a href=\"/c/next\">next</a>"));
        routes.put("/c/login", new Reply(302, Map.of("Location", List.of("/c/home"),
                "Set-Cookie", List.of("AUTH=1; Path=/")), ""));
        routes.put("/c/home", Reply.html("home"));
        routes.put("/c/next", Reply.html("next"));

        crawl(url("localhost", "/c/"), "zebra");

        assertEquals("AUTH=1", requests.stream().filter(r -> r.path().equals("/c/home"))
                .findFirst().orElseThrow().cookie());
        assertEquals("AUTH=1", requests.stream().filter(r -> r.path().equals("/c/next"))
                .findFirst().orElseThrow().cookie());
    }

    @Test
    public void testSeedRedirectToAnotherSiteMovesTheScope() {
        // A site that moved domains: the seed redirects, and the new domain's links are followed.
        routes.put("localhost/old", Reply.redirect(url("127.0.0.1", "/new")));
        routes.put("127.0.0.1/new", Reply.html("<a href=\"/new/page\">page</a>"));
        routes.put("127.0.0.1/new/page", Reply.html("zebra"));

        CrawlResult result = crawl(url("localhost", "/old"), "zebra");

        assertEquals(1, result.getTotalMatches());
        assertTrue(result.offsiteRedirects.isEmpty());
    }

    // ── depth, budget, dedup ─────────────────────────────────────────────────

    @Test
    public void testMetaRefreshSeedDoesNotUseUpADepthLevel() {
        routes.put("/m/", new Reply(200, Map.of("Content-Type", List.of("text/html")),
                "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/m/home\"></head><body></body></html>"));
        routes.put("/m/home", Reply.html("<a href=\"/m/a\">a</a> home"));
        routes.put("/m/a", Reply.html("zebra lives here"));

        // Default depth 1: the refresh target stands in for the seed, so its links are depth 1.
        CrawlResult result = crawl(url("localhost", "/m/"), "zebra");
        assertEquals(1, result.getTotalMatches());
    }

    @Test
    public void testBrokenLinksDoNotUseUpThePageBudget() {
        StringBuilder links = new StringBuilder();
        for (int i = 1; i <= 5; i++) links.append("<a href=\"/p/broken").append(i).append("\">b</a>");
        for (int i = 1; i <= 5; i++) {
            links.append("<a href=\"/p/good").append(i).append("\">g</a>");
            routes.put("/p/good" + i, Reply.html("zebra"));
        }
        routes.put("/p/", Reply.html(links.toString()));

        CrawlResult result = crawl(url("localhost", "/p/"), "zebra", "--max-pages", "6");

        assertEquals("The seed plus all five good pages fit the budget of 6", 6, result.visitedCount);
        assertEquals(5, result.getTotalMatches());
        assertEquals(5, (int) result.errorCounts.get(CrawlResult.ErrorType.NETWORK_ERROR));
    }

    @Test
    public void testMaxPagesStillCapsVisitedPages() {
        StringBuilder links = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            links.append("<a href=\"/cap/").append(i).append("\">x</a>");
            routes.put("/cap/" + i, Reply.html("zebra"));
        }
        routes.put("/cap/", Reply.html(links.toString()));

        CrawlResult result = crawl(url("localhost", "/cap/"), "zebra", "--max-pages", "4");
        assertEquals(4, result.visitedCount);
    }

    @Test
    public void testNextPageOfResultsIsNotTreatedAsDuplicate() {
        routes.put("/d/search?q=x", Reply.html("results page one <a href=\"/d/search?q=x&amp;page=2\">next</a>"
                + " <a href=\"/d/search?q=x&amp;sort=date\">by date</a>"));
        routes.put("/d/search?q=x&page=2", Reply.html("results page two: zebra"));
        routes.put("/d/search?q=x&sort=date", Reply.html("results sorted: zebra"));

        CrawlResult result = crawl(url("localhost", "/d/search?q=x"), "zebra");

        assertEquals("Page 2 is new content; the sort variant is not", 1, result.getTotalMatches());
        assertTrue(result.results.containsKey(url("localhost", "/d/search?q=x&page=2")));
        assertTrue(requests.stream().noneMatch(r -> r.path().contains("sort=date")));
    }

    // ── rate limits and size limits ──────────────────────────────────────────

    @Test
    public void testRateLimitedPageIsRetried() {
        routes.put("/rl/", Reply.html("<a href=\"/rl/busy\">busy</a>"));
        sequences.put("/rl/busy", new ArrayDeque<>(List.of(
                new Reply(429, Map.of("Retry-After", List.of("0"), "Set-Cookie", List.of("challenge=ok; Path=/")), ""),
                Reply.html("zebra after the wait"))));

        CrawlResult result = crawl(url("localhost", "/rl/"), "zebra");

        assertEquals(1, result.getTotalMatches());
        assertTrue(result.blockedUrls.isEmpty());
        // The cookie set on the 429 is sent back on the retry.
        List<Request> busy = requests.stream().filter(r -> r.path().equals("/rl/busy")).toList();
        assertEquals(2, busy.size());
        assertEquals("challenge=ok", busy.get(1).cookie());
    }

    @Test
    public void testPersistentRateLimitIsReportedAsBlocked() {
        routes.put("/rl2/", Reply.html("<a href=\"/rl2/busy\">busy</a>"));
        routes.put("/rl2/busy", new Reply(429, Map.of("Retry-After", List.of("0")), ""));

        CrawlResult result = crawl(url("localhost", "/rl2/"), "zebra");

        assertTrue(result.blockedUrls.containsKey(url("localhost", "/rl2/busy")));
        assertEquals("Three attempts in total", 3,
                requests.stream().filter(r -> r.path().equals("/rl2/busy")).count());
    }

    @Test
    public void testPageOverMaxBytesIsSkipped() {
        routes.put("/big/", Reply.html("<a href=\"/big/page\">big</a>"));
        routes.put("/big/page", Reply.html("zebra " + "x".repeat(5000)));

        CrawlResult result = crawl(url("localhost", "/big/"), "zebra", "--max-bytes", "1000");

        assertEquals(0, result.getTotalMatches());
        assertEquals(1, (int) result.errorCounts.get(CrawlResult.ErrorType.SKIPPED_SIZE));
    }

    // ── redirects to a variant of the requested URL ──────────────────────────

    @Test
    public void testRedirectToOwnVariantIsNotSkipped() {
        // http to https is the most common redirect. A plain-HTTP test server cannot redirect to
        // https, but a redirect that only adds a tracking parameter leads to a URL with the same
        // dedup key as the requested one, exactly as the https variant of an http URL does.
        routes.put("/v/", Reply.html("<a href=\"/v/page\">page</a>"));
        routes.put("/v/page", Reply.redirect("/v/page?utm_source=feed"));
        routes.put("/v/page?utm_source=feed", Reply.html("zebra on the page"));

        CrawlResult result = crawl(url("localhost", "/v/"), "zebra");

        assertEquals("The page a link redirects to must not be taken for a duplicate of that link",
                1, result.getTotalMatches());
    }

    @Test
    public void testSeedRedirectToOwnVariantIsNotSkipped() {
        routes.put("/w/?utm_source=mail", Reply.redirect("/w/"));
        routes.put("/w/", Reply.html("zebra at the seed"));

        CrawlResult result = crawl(url("localhost", "/w/?utm_source=mail"), "zebra");

        assertEquals(1, result.getTotalMatches());
        assertEquals(1, result.visitedCount);
    }

    @Test
    public void testTwoLinksRedirectingToOnePageCountItOnce() {
        routes.put("/two/", Reply.html("<a href=\"/two/a\">a</a> <a href=\"/two/b\">b</a>"));
        routes.put("/two/a", Reply.redirect("/two/target"));
        routes.put("/two/b", Reply.redirect("/two/target"));
        routes.put("/two/target", Reply.html("zebra"));

        CrawlResult result = crawl(url("localhost", "/two/"), "zebra");

        assertEquals(1, result.getTotalMatches());
    }
}
