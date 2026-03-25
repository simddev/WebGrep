package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.MatchEngine;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.FileMatch;
import com.webgrep.utils.UrlUtils;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class MainTest {

    @Test
    public void testNormalizeUrl() {
        assertEquals("http://example.com/", UrlUtils.normalizeUrl("example.com", null));
        assertEquals("https://example.com/path", UrlUtils.normalizeUrl("//example.com/path", "https://other.com"));
        assertEquals("http://example.com/a/b", UrlUtils.normalizeUrl("b", "http://example.com/a/"));
        assertEquals("http://example.com/a/c", UrlUtils.normalizeUrl("/a/c", "http://example.com/a/b"));
    }

    @Test
    public void testNonHttpSchemesFilteredByNormalizeUrl() {
        // ftp:// and file:// must be rejected so they never reach the fetcher
        assertEquals("", UrlUtils.normalizeUrl("ftp://example.com/file.txt", null));
        assertEquals("", UrlUtils.normalizeUrl("file:///etc/passwd", null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNonHttpSchemeInUrlOptionIsRejected() {
        // Passing ftp:// as the seed URL should fail validation with a clear message,
        // not silently produce 0 pages visited.
        String[] args = {"-u", "ftp://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testMatchEngine() {
        MatchEngine engine = new MatchEngine();

        // Default mode (case-insensitive)
        assertEquals(2, engine.countMatches("Hello world, hello!", "hello", "default"));

        // Exact mode
        assertEquals(1, engine.countMatches("Hello world, hello!", "hello", "exact"));
        assertEquals(0, engine.countMatches("Hello world", "HELLO", "exact"));

        // Fuzzy mode
        assertEquals(1, engine.countMatches("H.e.l.l.o", "hello", "fuzzy"));
        assertEquals(1, engine.countMatches("Café", "cafe", "fuzzy"));
    }

    @Test
    public void testSuperSimplify() {
        MatchEngine engine = new MatchEngine();
        assertEquals("cafe", engine.superSimplify("Café"));
        assertEquals("helloworld123", engine.superSimplify("Hello-World_123!"));
    }

    @Test
    public void testCliOptions() {
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "2", "-m", "exact"};
        CliOptions options = CliOptions.parse(args);
        options.validate();

        assertEquals("http://example.com", options.getUrl());
        assertEquals("test", options.getKeyword());
        assertEquals(2, options.getDepth());
        assertEquals("exact", options.getMode());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCliOptionsInvalidDepth() {
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "-1"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCliOptionsMissingUrl() {
        String[] args = {"-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testIsIgnoredLink() {
        // Should be ignored
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/style.css"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/IMAGE.PNG")); // case-insensitive
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/script.js"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/video.mp4"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/archive.zip"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/font.woff2"));
        assertTrue(UrlUtils.isIgnoredLink("https://www.facebook.com/sharer/share"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/author/john"));
        assertTrue(UrlUtils.isIgnoredLink("http://example.com/tag/java"));

        // Should NOT be ignored
        assertFalse(UrlUtils.isIgnoredLink("http://example.com/report.pdf"));
        assertFalse(UrlUtils.isIgnoredLink("http://example.com/document.docx"));
        assertFalse(UrlUtils.isIgnoredLink("http://example.com/readme.txt"));
        assertFalse(UrlUtils.isIgnoredLink("http://example.com/page"));
        assertFalse(UrlUtils.isIgnoredLink("http://example.com/page?q=test"));
    }

    @Test
    public void testCrawlResult() {
        CrawlResult result = new CrawlResult();
        result.addMatch("http://example.com", 5);
        result.incrementError(CrawlResult.ErrorType.NETWORK_ERROR);
        result.incrementError(CrawlResult.ErrorType.NETWORK_ERROR);
        result.addBlocked("http://blocked.com", "403");

        assertEquals(5, (int) result.results.get("http://example.com"));
        assertEquals(2, (int) result.errorCounts.get(CrawlResult.ErrorType.NETWORK_ERROR));
        assertEquals(0, (int) result.errorCounts.get(CrawlResult.ErrorType.PARSE_ERROR));
        assertEquals(1, (int) result.errorCounts.get(CrawlResult.ErrorType.BLOCKED));
        assertEquals("403", result.blockedUrls.get("http://blocked.com"));
    }

    @Test
    public void testAllUrlsOption() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--all-urls"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertTrue(options.isAllUrls());

        // short flag
        String[] args2 = {"-u", "http://example.com", "-k", "test", "-a"};
        CliOptions options2 = CliOptions.parse(args2);
        options2.validate();
        assertTrue(options2.isAllUrls());

        // off by default (query dedup is the default)
        String[] args3 = {"-u", "http://example.com", "-k", "test"};
        CliOptions options3 = CliOptions.parse(args3);
        assertFalse(options3.isAllUrls());
    }

    @Test
    public void testDelayMsOption() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--delay-ms", "500"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals(500, options.getDelayMs());
    }

    @Test
    public void testDfsOption() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--dfs"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertTrue(options.isDfs());

        // short flag
        String[] args2 = {"-u", "http://example.com", "-k", "test", "-s"};
        CliOptions options2 = CliOptions.parse(args2);
        options2.validate();
        assertTrue(options2.isDfs());

        // off by default
        String[] args3 = {"-u", "http://example.com", "-k", "test"};
        CliOptions options3 = CliOptions.parse(args3);
        assertFalse(options3.isDfs());
    }

    @Test
    public void testMaxHitsOption() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--max-hits", "5"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals(5, options.getMaxHits());

        // short flag
        String[] args2 = {"-u", "http://example.com", "-k", "test", "-n", "10"};
        CliOptions options2 = CliOptions.parse(args2);
        options2.validate();
        assertEquals(10, options2.getMaxHits());

        // default is 0 (no limit)
        String[] args3 = {"-u", "http://example.com", "-k", "test"};
        CliOptions options3 = CliOptions.parse(args3);
        assertEquals(0, options3.getMaxHits());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMaxHitsNegative() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--max-hits", "-1"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testCrawlResultDuration() {
        CrawlResult result = new CrawlResult();
        assertEquals(0L, result.durationMs);
        result.durationMs = 4250;
        assertEquals(4250L, result.durationMs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDelayMsNegative() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--delay-ms", "-1"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testFileOptionParsed() {
        String[] args = {"-f", "/tmp/file.pdf", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertEquals("/tmp/file.pdf", options.getFile());
        assertNull(options.getUrl());
    }

    @Test
    public void testFileModeValidationRequiresKeyword() {
        // --file without --keyword must still fail
        String[] args = {"-f", "/tmp/file.pdf"};
        CliOptions options = CliOptions.parse(args);
        try {
            options.validate();
            fail("Expected IllegalArgumentException for missing keyword");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Keyword"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFileModeAndUrlMutuallyExclusive() {
        String[] args = {"-f", "/tmp/file.pdf", "-u", "http://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testFindLineMatchesBasic() {
        MatchEngine engine = new MatchEngine();
        String text = "no match here\nThe quick brown fox\nanother fox line\nnothing";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);

        assertEquals(2, matches.size());
        assertEquals(2, matches.get(0).line());
        assertEquals(1, matches.get(0).count());
        assertEquals("The quick brown fox", matches.get(0).snippet());
        assertEquals(3, matches.get(1).line());
        // page=0 means no page structure
        assertEquals(0, matches.get(0).page());
    }

    @Test
    public void testFindLineMatchesWithPages() {
        MatchEngine engine = new MatchEngine();
        // \f is Tika's page separator
        String text = "intro line\nfox on page one\f\nno match\nfox on page two";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);

        assertEquals(2, matches.size());
        assertEquals(1, matches.get(0).page());
        assertEquals(2, matches.get(0).line());
        assertEquals(2, matches.get(1).page());
        assertEquals(3, matches.get(1).line());
    }

    @Test
    public void testFindLineMatchesMultipleMatchesPerLine() {
        MatchEngine engine = new MatchEngine();
        String text = "fox and fox and fox";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);

        assertEquals(1, matches.size());
        assertEquals(3, matches.get(0).count());
        assertEquals(1, matches.get(0).line());
    }

    @Test
    public void testFindLineMatchesSnippetTruncated() {
        MatchEngine engine = new MatchEngine();
        String longLine = "fox " + "x".repeat(200);
        List<FileMatch> matches = Main.findLineMatches(longLine, "fox", "default", engine);

        assertEquals(1, matches.size());
        assertTrue(matches.get(0).snippet().length() <= 120);
        assertTrue(matches.get(0).snippet().endsWith("..."));
    }
}
