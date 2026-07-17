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
    public void testFuzzyModeLevenshteinSecondPass() {
        MatchEngine engine = new MatchEngine();
        // "kiten" is edit distance 1 from "kitten". superSimplify won't find "kitten" as a
        // substring of "kiten", so the Levenshtein word-level pass must catch it.
        // Threshold for "kitten" (6 chars > 4) is ≤ 2; distance is 1.
        assertEquals(1, engine.countMatches("The kiten sat on the mat", "kitten", "fuzzy"));
        // Keyword ≤ 4 chars uses threshold 1; "helo" (distance 1 from "hell") must also match.
        assertEquals(1, engine.countMatches("I said helo to them", "hell", "fuzzy"));
    }

    @Test
    public void testMatchEngineSingleCharFallbackSuppressed() {
        MatchEngine engine = new MatchEngine();
        // "(C)" strips to "c" and "C++" strips to "c" - a 1-char fallback must not
        // match every line in a document; it should return 0 when the literal is absent.
        assertEquals(0, engine.countMatches("GNU GENERAL PUBLIC LICENSE", "(C)", "default"));
        assertEquals(0, engine.countMatches("Use the GNU General Public License", "C++", "default"));
        assertEquals(0, engine.countMatches("GNU GENERAL PUBLIC LICENSE", "(C)", "fuzzy"));
        // But the literal does match when it's actually present
        assertEquals(1, engine.countMatches("Copyright (C) 2007 FSF", "(C)", "default"));
        // And diacritic stripping still works for multi-char simplifications
        assertEquals(1, engine.countMatches("Café is open", "cafe", "default"));
    }

    @Test
    public void testSuperSimplify() {
        MatchEngine engine = new MatchEngine();
        assertEquals("cafe", engine.superSimplify("Café"));
        assertEquals("helloworld123", engine.superSimplify("Hello-World_123!"));
    }

    @Test
    public void testFindSnippetsBasic() {
        MatchEngine engine = new MatchEngine();
        // Two "hello" occurrences far enough apart that their context windows produce distinct snippets
        String text = "Hello there " + "word ".repeat(20) + "saying hello again at the end";
        List<String> snips = engine.findSnippets(text, "hello", "default", 5);
        assertEquals(2, snips.size());
        assertTrue(snips.get(0).toLowerCase().contains("hello"));
    }

    @Test
    public void testFindSnippetsRespectsMaxSnippets() {
        MatchEngine engine = new MatchEngine();
        // Four occurrences spaced far apart; ask for max 2
        String text = "fox alpha " + "word ".repeat(20) + "fox beta " + "word ".repeat(20) + "fox gamma " + "word ".repeat(20) + "fox delta";
        List<String> snips = engine.findSnippets(text, "fox", "default", 2);
        assertEquals(2, snips.size());
    }

    @Test
    public void testFindSnippetsIncludesSurroundingContext() {
        MatchEngine engine = new MatchEngine();
        List<String> snips = engine.findSnippets("The quick brown fox jumps over the lazy dog", "fox", "default", 3);
        assertEquals(1, snips.size());
        assertTrue(snips.get(0).contains("brown fox jumps"));
    }

    @Test
    public void testFindSnippetsEmptyTextReturnsEmpty() {
        MatchEngine engine = new MatchEngine();
        assertEquals(0, engine.findSnippets("", "fox", "default", 3).size());
        assertEquals(0, engine.findSnippets(null, "fox", "default", 3).size());
    }

    @Test
    public void testFindSnippetsExactModeIsCaseSensitive() {
        MatchEngine engine = new MatchEngine();
        List<String> snips = engine.findSnippets("Hello world, hello there", "hello", "exact", 5);
        assertEquals(1, snips.size());
        assertTrue(snips.get(0).contains("hello there"));
    }

    @Test
    public void testFindSnippetsCatchesDiacriticVariant() {
        MatchEngine engine = new MatchEngine();
        // "Tomas" (ASCII) must match "Tomáš" (diacritic) via simplified pass and show original text
        List<String> snips = engine.findSnippets("Kontaktni osoba je Tomáš Novák ze dne 2024.", "Tomas", "default", 3);
        assertEquals(1, snips.size());
        assertTrue(snips.get(0).contains("Tomáš"));
    }

    @Test
    public void testFindSnippetsDefaultModeCatchesDiacriticVariantWhenRegexAlsoFound() {
        MatchEngine engine = new MatchEngine();
        // Regression: simplified pass was guarded by results.isEmpty(). When regex already found
        // "cafe", the pass was skipped and "Café" got no snippet. Now the guard is
        // results.size() < maxSnippets, so both variants produce a snippet.
        String text = "cafe here " + "word ".repeat(25) + "then Café appears";
        List<String> snips = engine.findSnippets(text, "cafe", "default", 5);
        assertEquals(2, snips.size());
        assertTrue(snips.stream().anyMatch(s -> s.contains("cafe")));
        assertTrue(snips.stream().anyMatch(s -> s.contains("Café")));
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
    public void testClassifyExceptionMapsKnownTypes() {
        // classifyException is private; verify its mapping indirectly via addNetworkError.
        CrawlResult result = new CrawlResult();
        result.addNetworkError(new java.net.SocketTimeoutException("connect timed out"));
        result.addNetworkError(new java.net.UnknownHostException("host.example.com"));
        result.addNetworkError(new java.net.ConnectException("Connection refused"));
        result.addNetworkError(new javax.net.ssl.SSLHandshakeException("bad cert"));
        result.addNetworkError(new javax.net.ssl.SSLPeerUnverifiedException("cert not trusted"));

        assertEquals(5, (int) result.errorCounts.get(CrawlResult.ErrorType.NETWORK_ERROR));
        assertEquals(1, (int) result.networkErrorReasons.get("Timeout"));
        assertEquals(1, (int) result.networkErrorReasons.get("DNS failure"));
        assertEquals(1, (int) result.networkErrorReasons.get("Connection refused"));
        assertEquals(2, (int) result.networkErrorReasons.get("SSL error"));
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
    // findLineMatches - edge cases
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
        String line = "fox " + "x".repeat(117); // 121 chars - must truncate
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
    // CliOptions - --file flag, regressions
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
    // CliOptions - --folder flag
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
    // CliOptions - unknown flag rejection
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

    // ────────────────────────────────────────────────────────────────
    // CliOptions - --browser flag
    // ────────────────────────────────────────────────────────────────

    @Test
    public void testBrowserDefaultIsAuto() {
        String[] args = {"-u", "http://example.com", "-k", "test"};
        CliOptions options = CliOptions.parse(args);
        assertEquals("auto", options.getBrowser());
    }

    @Test
    public void testBrowserFirefox() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--browser", "firefox"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals("firefox", options.getBrowser());
    }

    @Test
    public void testBrowserChromium() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--browser", "chromium"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals("chromium", options.getBrowser());
    }

    @Test
    public void testBrowserCaseInsensitive() {
        String[] args = {"-u", "http://example.com", "-k", "test", "--browser", "Firefox"};
        CliOptions options = CliOptions.parse(args);
        options.validate();
        assertEquals("firefox", options.getBrowser());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBrowserInvalidValueRejected() {
        CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "test", "--browser", "safari"});
    }

    @Test
    public void testInstallBrowserFlag() {
        String[] args = {"--install-browser"};
        CliOptions options = CliOptions.parse(args);
        assertTrue(options.isInstallBrowser());
        assertEquals("auto", options.getBrowser());
    }

    @Test
    public void testInstallBrowserWithBrowserPreference() {
        String[] args = {"--install-browser", "--browser", "chromium"};
        CliOptions options = CliOptions.parse(args);
        assertTrue(options.isInstallBrowser());
        assertEquals("chromium", options.getBrowser());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInstallBrowserWithInvalidBrowserRejected() {
        CliOptions.parse(new String[]{"--install-browser", "--browser", "ie"});
    }

    // ────────────────────────────────────────────────────────────────
    // Regression tests for v1.1.3 fixes
    // ────────────────────────────────────────────────────────────────

    @Test
    public void testAddMatchAccumulatesAcrossVisits() {
        // v1.1.3: addMatch must use merge, not put - second call must add to existing count
        CrawlResult result = new CrawlResult();
        result.addMatch("http://example.com/page", 3);
        result.addMatch("http://example.com/page", 2);
        assertEquals(5, (int) result.results.get("http://example.com/page"));
        assertEquals(5, result.getTotalMatches());
    }

    @Test
    public void testDefaultModeMixedAccentAndPlainCounting() {
        // v1.1.3: "cafe Café" with keyword "cafe" must count both, not just the plain one.
        // Before the fix, the regex matched only "cafe" (count=1) and the simplified
        // fallback was skipped, so "Café" was missed.
        MatchEngine engine = new MatchEngine();
        assertEquals(2, engine.countMatches("cafe Café", "cafe", "default"));
        assertEquals(2, engine.countMatches("Café cafe", "cafe", "default"));
        assertEquals(2, engine.countMatches("Café cafe", "café", "default"));
        assertEquals(4, engine.countMatches("cafe Café café CAFE", "cafe", "default"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingFlagValueThrows() {
        // -u requires a value; -k as the next arg must not silently become the URL
        CliOptions.parse(new String[]{"-u", "-k", "keyword"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testMissingFlagValueAtEndThrows() {
        // -u at end of args with no value must throw
        CliOptions.parse(new String[]{"-u"});
    }

    @Test
    public void testNegativeNumberIsValidFlagValue() {
        // -d -1 should parse as depth=-1 (negative numbers are valid values, not flags)
        CliOptions opts = CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "x", "-d", "-1"});
        assertEquals(-1, opts.getDepth());
    }

    @Test
    public void testFolderScanSkipsSymlinks() throws Exception {
        // v1.1.3: symlinks must be excluded from the folder scan
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("webgrep-symlink-test");
        try {
            java.nio.file.Path real = tmp.resolve("real.txt");
            java.nio.file.Files.writeString(real, "keyword found here");
            // Symlink pointing at the real file - should NOT be scanned as a second copy
            java.nio.file.Path link = tmp.resolve("link.txt");
            java.nio.file.Files.createSymbolicLink(link, real);

            MatchEngine engine = new MatchEngine();
            com.webgrep.core.ContentExtractor extractor =
                    new com.webgrep.core.ContentExtractor(10 * 1024 * 1024);

            // Walk the directory: only real.txt should be seen, not link.txt
            java.util.List<java.nio.file.Path> files;
            try (var stream = java.nio.file.Files.walk(tmp)) {
                files = stream
                        .filter(p -> java.nio.file.Files.isRegularFile(p)
                                && !java.nio.file.Files.isSymbolicLink(p))
                        .sorted()
                        .toList();
            }
            assertEquals("Only one non-symlink file should be scanned", 1, files.size());
            assertEquals(real, files.get(0));
        } finally {
            // cleanup
            try (var s = java.nio.file.Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> { try { java.nio.file.Files.delete(p); } catch (Exception ignored) {} });
            }
        }
    }

    @Test
    public void testTotalMatchesRunningCounter() {
        // P-01: getTotalMatches() must use the running counter, not recompute via stream
        CrawlResult result = new CrawlResult();
        assertEquals(0, result.getTotalMatches());
        result.addMatch("http://a.com/p1", 5);
        assertEquals(5, result.getTotalMatches());
        result.addMatch("http://a.com/p2", 3);
        assertEquals(8, result.getTotalMatches());
        // Same URL visited again - count accumulates
        result.addMatch("http://a.com/p1", 2);
        assertEquals(10, result.getTotalMatches());
    }

    @Test
    public void testNormalizeUrlRejectsJavascriptScheme() {
        // B-23: javascript: URIs must never reach the fetcher
        assertEquals("", UrlUtils.normalizeUrl("javascript:alert(1)", null));
        assertEquals("", UrlUtils.normalizeUrl("javascript:void(0)", "https://example.com/"));
    }

    @Test
    public void testNormalizeUrlRejectsDataScheme() {
        // B-23: data: URIs must be filtered even though they contain text content
        assertEquals("", UrlUtils.normalizeUrl("data:text/html,<h1>hi</h1>", null));
    }

    @Test
    public void testNormalizeUrlRejectsFtpScheme() {
        // B-23: ftp:// must be rejected (only http/https are crawlable)
        assertEquals("", UrlUtils.normalizeUrl("ftp://files.example.com/readme.txt", null));
    }

    @Test
    public void testSpaDetectionNotTriggeredBySingleAnalyticsScript() {
        // B-11: a normal page with one analytics script and decent body text is NOT an SPA
        String html = "<html><head><script src='analytics.js'></script></head>"
                + "<body>Welcome to our site! This page has real content about products and services. "
                + "Click here to learn more about what we offer.</body></html>";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        assertFalse("Single analytics script + text > 100 chars must not trigger SPA",
                com.webgrep.core.PlaywrightRenderer.isSpa(doc));
    }

    @Test
    public void testFindSnippetsFuzzyWithAsciiSpecialKeyword() {
        MatchEngine engine = new MatchEngine();
        // "node.js" in fuzzy mode: countFuzzyMatches strips the dot and finds "nodejs",
        // so findSnippets must also find it via the simplified pass (fuzzy exemption to ASCII-special guard).
        List<String> snips = engine.findSnippets(
                "The nodejs ecosystem is popular. The node.js runtime is used everywhere.", "node.js", "fuzzy", 5);
        assertTrue("Fuzzy mode must find 'nodejs' even for an ASCII-special keyword", snips.size() > 0);
    }

    @Test
    public void testFindLineMatchesWhitespaceOnlyText() {
        MatchEngine engine = new MatchEngine();
        assertTrue(Main.findLineMatches("   \n  \t  \n  ", "fox", "default", engine).isEmpty());
        assertTrue(Main.findLineMatches("\n\n\n", "fox", "default", engine).isEmpty());
    }

    @Test
    public void testSpaDetectionTriggeredOnEmptyBody() {
        // B-11: a page with very short body text and any JS is still detected as SPA
        String html = "<html><head><script src='app.js'></script></head>"
                + "<body>Loading...</body></html>";
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        assertTrue("Very short body + JS bundle must still be detected as SPA",
                com.webgrep.core.PlaywrightRenderer.isSpa(doc));
    }

    // ── H-1: word-boundary false positives in default mode ───────────────────

    @Test
    public void testDefaultModeDoesNotMatchAcrossWordBoundaries() {
        MatchEngine engine = new MatchEngine();
        // "sofa" must not match "so far away" - the space between "so" and "far"
        // means there is no contiguous occurrence of the keyword.
        assertEquals(0, engine.countMatches("so far away", "sofa", "default"));
        assertEquals(0, engine.countMatches("the so far away journey", "sofa", "default"));
    }

    @Test
    public void testDefaultModeStillMatchesDiacriticVariants() {
        MatchEngine engine = new MatchEngine();
        // The word-boundary fix must not break diacritic matching.
        assertEquals(1, engine.countMatches("Café is great", "cafe", "default"));
        assertEquals(2, engine.countMatches("Café and cafe", "cafe", "default"));
        assertEquals(1, engine.countMatches("Tomáš visited", "tomas", "default"));
    }

    @Test
    public void testDefaultModeSnippetDoesNotMatchAcrossWordBoundaries() {
        MatchEngine engine = new MatchEngine();
        List<String> snippets = engine.findSnippets("so far away", "sofa", "default", 5);
        assertTrue("sofa must not produce a snippet in 'so far away'", snippets.isEmpty());
    }

    @Test
    public void testFuzzyModeStillMatchesAcrossWordBoundaries() {
        MatchEngine engine = new MatchEngine();
        // Fuzzy mode intentionally strips spaces so "sofa" CAN match "so far away"
        // (space-stripped text is "sofaraway"). This is expected fuzzy behaviour.
        assertTrue(engine.countMatches("so far away", "sofa", "fuzzy") > 0);
    }

    // ── M-4: malformed flag rejection ─────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void testMultiCharSingleDashFlagIsRejected() {
        // -url is not a valid flag; --url is correct.
        CliOptions.parse(new String[]{"-url", "http://example.com", "-k", "test"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCombinedFlagsAreRejected() {
        // -ke looks like a combined flag but WebGrep does not support flag combination.
        CliOptions.parse(new String[]{"-ke", "test"});
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPositionalArgIsRejected() {
        // A bare word with no flag prefix must throw, not silently disappear.
        CliOptions.parse(new String[]{"http://example.com", "-k", "test"});
    }

    // ── L-5: URL with no host ─────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void testUrlWithNoHostIsRejected() {
        CliOptions options = CliOptions.parse(new String[]{"-u", "http://", "-k", "test"});
        options.validate();
    }

    // ── L-6: timeout = 0 ─────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void testTimeoutZeroIsRejected() {
        // timeout 0 means infinite timeout in Jsoup; must be rejected.
        CliOptions options = CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "test", "-t", "0"});
        options.validate();
    }
}
