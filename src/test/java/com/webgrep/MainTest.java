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

    // ────────────────────────────────────────────────────────────────
    // findLineMatches — edge cases
    // ────────────────────────────────────────────────────────────────

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

    @Test
    public void testFindLineMatchesNoMatches() {
        MatchEngine engine = new MatchEngine();
        List<FileMatch> matches = Main.findLineMatches("no keyword here\nneither does this", "fox", "default", engine);
        assertTrue(matches.isEmpty());
    }

    @Test
    public void testFindLineMatchesEmptyText() {
        assertTrue(Main.findLineMatches("", "fox", "default", new MatchEngine()).isEmpty());
    }

    @Test
    public void testFindLineMatchesNullText() {
        assertTrue(Main.findLineMatches(null, "fox", "default", new MatchEngine()).isEmpty());
    }

    @Test
    public void testFindLineMatchesFirstLine() {
        // Line numbers are 1-indexed; a match on the very first line must be l.1
        MatchEngine engine = new MatchEngine();
        List<FileMatch> matches = Main.findLineMatches("fox is first\nsecond line", "fox", "default", engine);
        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).line());
        assertEquals(0, matches.get(0).page()); // no page structure
    }

    @Test
    public void testFindLineMatchesCaseInsensitiveDefault() {
        MatchEngine engine = new MatchEngine();
        String text = "Fox is upper\nfox is lower\nFOX is all caps";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        assertEquals(3, matches.size());
    }

    @Test
    public void testFindLineMatchesExactModeIsCaseSensitive() {
        MatchEngine engine = new MatchEngine();
        String text = "Fox is upper\nfox is lower\nFOX is all caps";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "exact", engine);
        // Only the exact-case "fox" matches
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).line());
    }

    @Test
    public void testFindLineMatchesFuzzyMode() {
        MatchEngine engine = new MatchEngine();
        String text = "nothing here\ncafe keyword present\nstill nothing";
        List<FileMatch> matches = Main.findLineMatches(text, "café", "fuzzy", engine);
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).line());
    }

    @Test
    public void testFindLineMatchesWindowsLineEndings() {
        // \r\n must be treated as a single newline, keeping line numbers correct
        MatchEngine engine = new MatchEngine();
        String text = "line one\r\nfox here\r\nline three";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).line());
        assertEquals("fox here", matches.get(0).snippet());
    }

    @Test
    public void testFindLineMatchesCarriageReturnOnlyLineEndings() {
        // Old Mac-style \r-only endings must also be normalised
        MatchEngine engine = new MatchEngine();
        String text = "line one\rfox here\rline three";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        assertEquals(1, matches.size());
        assertEquals(2, matches.get(0).line());
    }

    @Test
    public void testFindLineMatchesLineNumbersResetPerPage() {
        // Line numbers restart at 1 on each page
        MatchEngine engine = new MatchEngine();
        String text = "p1 line1\np1 line2\np1 fox line3\fp2 line1\np2 fox line2";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        assertEquals(2, matches.size());
        assertEquals(1, matches.get(0).page());
        assertEquals(3, matches.get(0).line());
        assertEquals(2, matches.get(1).page());
        assertEquals(2, matches.get(1).line());
    }

    @Test
    public void testFindLineMatchesPageNumbersIncrement() {
        // Matches on page 2 and page 4 must reflect correct page numbers
        MatchEngine engine = new MatchEngine();
        String text = "p1\ffox on p2\fno match\ffox on p4";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        assertEquals(2, matches.size());
        assertEquals(2, matches.get(0).page());
        assertEquals(4, matches.get(1).page());
    }

    @Test
    public void testFindLineMatchesSnippetAt120NotTruncated() {
        MatchEngine engine = new MatchEngine();
        String line = "fox " + "x".repeat(116); // exactly 120 chars after trim
        List<FileMatch> matches = Main.findLineMatches(line, "fox", "default", engine);
        assertEquals(1, matches.size());
        assertEquals(120, matches.get(0).snippet().length());
        assertFalse(matches.get(0).snippet().endsWith("..."));
    }

    @Test
    public void testFindLineMatchesSnippetAt121IsTruncatedTo120() {
        MatchEngine engine = new MatchEngine();
        String line = "fox " + "x".repeat(117); // 121 chars — must truncate
        List<FileMatch> matches = Main.findLineMatches(line, "fox", "default", engine);
        assertEquals(1, matches.size());
        assertEquals(120, matches.get(0).snippet().length());
        assertTrue(matches.get(0).snippet().endsWith("..."));
    }

    @Test
    public void testFindLineMatchesTotalCountSumsAcrossLines() {
        MatchEngine engine = new MatchEngine();
        String text = "fox fox\nfox\nno match\nfox fox fox";
        List<FileMatch> matches = Main.findLineMatches(text, "fox", "default", engine);
        int total = matches.stream().mapToInt(FileMatch::count).sum();
        assertEquals(6, total);
        assertEquals(3, matches.size()); // 3 lines have at least one match
    }

    // ────────────────────────────────────────────────────────────────
    // CliOptions — --file flag, regressions
    // ────────────────────────────────────────────────────────────────

    @Test
    public void testFileOptionLongFlag() {
        String[] args = {"--file", "/tmp/test.pdf", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertEquals("/tmp/test.pdf", options.getFile());
        assertNull(options.getUrl());
    }

    @Test
    public void testGetFileIsNullInUrlMode() {
        // Existing URL mode must not accidentally expose a file path
        String[] args = {"-u", "http://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertNull(options.getFile());
    }

    @Test
    public void testFileModeAcceptsMatchModeOption() {
        String[] args = {"-f", "/tmp/test.pdf", "-k", "test", "-m", "exact"};
        CliOptions options = CliOptions.parse(args);
        options.validate(); // must not throw
        assertEquals("exact", options.getMode());
    }

    @Test
    public void testFileModeAcceptsJsonOutput() {
        String[] args = {"-f", "/tmp/test.pdf", "-k", "test", "-o", "json"};
        CliOptions options = CliOptions.parse(args);
        options.validate(); // must not throw
        assertEquals("json", options.getOutput());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFileModeRejectsInvalidMatchMode() {
        String[] args = {"-f", "/tmp/test.pdf", "-k", "test", "-m", "invalid"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testUrlModeValidationUnchangedAfterFileFeature() {
        // The URL validation path must still work exactly as before
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "3", "-m", "fuzzy"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals("http://example.com", options.getUrl());
        assertEquals(3, options.getDepth());
        assertEquals("fuzzy", options.getMode());
        assertNull(options.getFile());
        assertNull(options.getFolder());
    }

    // ────────────────────────────────────────────────────────────────
    // CliOptions — --folder flag
    // ────────────────────────────────────────────────────────────────

    @Test
    public void testFolderOptionShortFlag() {
        String[] args = {"-F", "/tmp/docs", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertEquals("/tmp/docs", options.getFolder());
        assertNull(options.getUrl());
        assertNull(options.getFile());
    }

    @Test
    public void testFolderOptionLongFlag() {
        String[] args = {"--folder", "/tmp/docs", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertEquals("/tmp/docs", options.getFolder());
    }

    @Test
    public void testFolderModeValidationRequiresKeyword() {
        String[] args = {"-F", "/tmp/docs"};
        CliOptions options = CliOptions.parse(args);
        try {
            options.validate();
            fail("Expected IllegalArgumentException for missing keyword");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Keyword"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFolderAndFileMutuallyExclusive() {
        String[] args = {"-F", "/tmp/docs", "-f", "/tmp/file.pdf", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testFolderAndUrlMutuallyExclusive() {
        String[] args = {"-F", "/tmp/docs", "-u", "http://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAllThreeMutuallyExclusive() {
        String[] args = {"-u", "http://example.com", "-f", "/tmp/f.pdf", "-F", "/tmp/docs", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
    }

    @Test
    public void testFolderModeAcceptsMatchModeOption() {
        String[] args = {"-F", "/tmp/docs", "-k", "test", "-m", "fuzzy"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals("fuzzy", options.getMode());
    }

    @Test
    public void testGetFolderIsNullInUrlMode() {
        String[] args = {"-u", "http://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertNull(options.getFolder());
    }

    @Test
    public void testGetFolderIsNullInFileMode() {
        String[] args = {"-f", "/tmp/file.pdf", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertNull(options.getFolder());
    }

    // ────────────────────────────────────────────────────────────────
    // CliOptions — unknown flag rejection
    // ────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void testBlankKeywordIsRejected() {
        CliOptions options = CliOptions.parse(new String[]{"-f", "/tmp/test.pdf", "-k", "   "});
        options.validate();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownLongFlagIsRejected() {
        CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "test", "--typo-flag"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownShortFlagIsRejected() {
        CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "test", "-Z"});
    }

    @Test
    public void testUnknownFlagErrorMessageNamesFlagClearly() {
        try {
            CliOptions.parse(new String[]{"--no-such-flag", "-k", "test"});
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("--no-such-flag"));
        }
    }
}
