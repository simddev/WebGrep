package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.FileMatch;
import com.webgrep.reporting.ReportWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.webgrep.reporting.FileScanResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class ReportWriterTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream out;

    @Before
    public void captureOutput() {
        out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
    }

    @After
    public void restoreOutput() {
        System.setOut(originalOut);
    }

    private CrawlResult sampleResult() {
        CrawlResult result = new CrawlResult();
        result.visitedCount = 2;
        result.parsedCount = 2;
        result.addMatch("http://example.com/a", 3);
        result.addMatch("http://example.com/b", 1);
        result.addBlocked("http://example.com/blocked", "403");
        return result;
    }

    private CliOptions sampleOptions() {
        String[] args = {"-u", "http://example.com", "-k", "test"};
        return CliOptions.parse(args);
    }

    @Test
    public void testTextOutputTotalMatches() {
        new ReportWriter().printTextOutput(sampleResult());
        String output = out.toString();
        assertTrue(output.contains("Total matches found: 4"));
    }

    @Test
    public void testTextOutputSortedByCount() {
        new ReportWriter().printTextOutput(sampleResult());
        String output = out.toString();
        // Higher count should appear before lower count
        int posA = output.indexOf("http://example.com/a (3)");
        int posB = output.indexOf("http://example.com/b (1)");
        assertTrue(posA > 0 && posB > 0 && posA < posB);
    }

    @Test
    public void testTextOutputShowsBlockedUrls() {
        new ReportWriter().printTextOutput(sampleResult());
        assertTrue(out.toString().contains("blocked"));
    }

    @Test
    public void testJsonOutputStructure() {
        new ReportWriter().printJsonOutput(sampleResult(), sampleOptions());
        String json = out.toString();
        assertTrue(json.contains("\"query\""));
        assertTrue(json.contains("\"stats\""));
        assertTrue(json.contains("\"results\""));
        assertTrue(json.contains("\"blocked\""));
        assertTrue(json.contains("\"duration_ms\""));
        assertTrue(json.contains("\"total_matches\": 4"));
        assertTrue(json.contains("\"pages_visited\": 2"));
        assertTrue(json.contains("\"pages_parsed\": 2"));
    }

    @Test
    public void testJsonOutputEscapesQuotes() {
        CrawlResult result = new CrawlResult();
        result.addMatch("http://example.com/path", 1);
        CliOptions options = CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "say \"hello\""});
        new ReportWriter().printJsonOutput(result, options);
        String json = out.toString();
        assertTrue(json.contains("say \\\"hello\\\""));
        assertFalse(json.contains("say \"hello\""));
    }

    @Test
    public void testJsonOutputEscapesControlCharacters() {
        CrawlResult result = new CrawlResult();
        result.addMatch("http://example.com/\u0001\u0002path", 1);
        new ReportWriter().printJsonOutput(result, sampleOptions());
        String json = out.toString();
        assertFalse(json.contains("\u0001"));
        assertFalse(json.contains("\u0002"));
        assertTrue(json.contains("\\u0001"));
        assertTrue(json.contains("\\u0002"));
    }

    @Test
    public void testJsonOutputEscapesNewlineInKeyword() {
        CrawlResult result = new CrawlResult();
        CliOptions options = CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "line1\nline2"});
        new ReportWriter().printJsonOutput(result, options);
        String json = out.toString();
        assertFalse(json.contains("line1\nline2"));
        assertTrue(json.contains("line1\\nline2"));
    }

    // ── regression: web crawl JSON must still have url + depth ────────────────

    @Test
    public void testWebCrawlJsonOutputHasUrlAndDepthFields() {
        String[] args = {"-u", "http://example.com", "-k", "test", "-d", "3"};
        CliOptions options = CliOptions.parse(args);
        new ReportWriter().printJsonOutput(new CrawlResult(), options);
        String json = out.toString();
        assertTrue(json.contains("\"url\": \"http://example.com\""));
        assertTrue(json.contains("\"depth\": 3"));
        assertFalse("File field must not appear in web crawl JSON", json.contains("\"file\""));
    }

    @Test
    public void testWebCrawlJsonOutputDefaultDepthIsOne() {
        CliOptions options = CliOptions.parse(new String[]{"-u", "http://example.com", "-k", "x"});
        new ReportWriter().printJsonOutput(new CrawlResult(), options);
        assertTrue(out.toString().contains("\"depth\": 1"));
    }

    // ── file mode text output ─────────────────────────────────────────────────

    private CliOptions fileOpts(String file, String keyword) {
        return CliOptions.parse(new String[]{"-f", file, "-k", keyword});
    }

    @Test
    public void testFileTextOutputNoMatches() {
        new ReportWriter().printFileTextOutput(
                fileOpts("/tmp/doc.txt", "fox"), Collections.emptyList(), 100);
        String output = out.toString();
        assertTrue(output.contains("--- WebGrep Results ---"));
        assertTrue(output.contains("File: /tmp/doc.txt"));
        assertTrue(output.contains("Total matches found: 0"));
        assertFalse("'Matches:' section must not appear when there are no matches",
                output.contains("Matches:"));
    }

    @Test
    public void testFileTextOutputWithMatchesNoPages() {
        List<FileMatch> matches = Arrays.asList(
                new FileMatch(0, 3, 1, "The quick brown fox"),
                new FileMatch(0, 7, 2, "fox and fox here")
        );
        new ReportWriter().printFileTextOutput(fileOpts("/tmp/doc.txt", "fox"), matches, 50);
        String output = out.toString();

        assertTrue(output.contains("Total matches found: 3")); // 1+2
        assertTrue(output.contains("Matches:"));
        assertTrue(output.contains("l.3"));
        assertTrue(output.contains("l.7"));
        assertTrue(output.contains("(1 match)"));
        assertTrue(output.contains("(2 matches)"));
        assertTrue(output.contains("The quick brown fox"));
        assertFalse("Page column must not appear when page=0", output.contains("p."));
    }

    @Test
    public void testFileTextOutputWithPageNumbers() {
        List<FileMatch> matches = Arrays.asList(
                new FileMatch(1, 3, 1, "fox on page one"),
                new FileMatch(3, 5, 2, "fox on page three")
        );
        new ReportWriter().printFileTextOutput(fileOpts("/tmp/doc.pdf", "fox"), matches, 200);
        String output = out.toString();
        assertTrue(output.contains("p.1, l.3"));
        assertTrue(output.contains("p.3, l.5"));
    }

    @Test
    public void testFileTextOutputAnyPageNonZeroTriggersPageFormat() {
        // Even if the first match has page=0, a later match with page>0 switches the format
        List<FileMatch> matches = Arrays.asList(
                new FileMatch(0, 1, 1, "first"),
                new FileMatch(2, 4, 1, "second")
        );
        new ReportWriter().printFileTextOutput(fileOpts("/tmp/f.pdf", "x"), matches, 10);
        assertTrue(out.toString().contains("p.2, l.4"));
    }

    @Test
    public void testFileTextOutputDurationSeconds() {
        new ReportWriter().printFileTextOutput(
                fileOpts("/tmp/f.txt", "x"), Collections.emptyList(), 1500);
        assertTrue(out.toString().contains("Duration: 1.50s"));
    }

    @Test
    public void testFileTextOutputDurationMinutes() {
        new ReportWriter().printFileTextOutput(
                fileOpts("/tmp/f.txt", "x"), Collections.emptyList(), 90_000);
        String output = out.toString();
        assertTrue(output.contains("1m"));
        assertTrue(output.contains("30.00s"));
    }

    @Test
    public void testFileTextOutputDoesNotPrintWebCrawlFields() {
        List<FileMatch> matches = List.of(new FileMatch(0, 1, 1, "match"));
        new ReportWriter().printFileTextOutput(fileOpts("/tmp/f.txt", "x"), matches, 100);
        String output = out.toString();
        assertFalse("Web crawl 'Pages visited' must not appear in file mode", output.contains("Pages visited"));
        assertFalse("Web crawl 'HTML pages' must not appear in file mode", output.contains("HTML pages"));
    }

    // ── file mode JSON output ─────────────────────────────────────────────────

    @Test
    public void testFileJsonOutputStructure() {
        List<FileMatch> matches = List.of(new FileMatch(0, 5, 2, "keyword here"));
        new ReportWriter().printFileJsonOutput(
                fileOpts("/tmp/test.pdf", "keyword"), matches, 123);
        String json = out.toString();

        assertTrue(json.contains("\"file\": \"/tmp/test.pdf\""));
        assertTrue(json.contains("\"keyword\": \"keyword\""));
        assertTrue(json.contains("\"duration_ms\": 123"));
        assertTrue(json.contains("\"total_matches\": 2"));
        assertTrue(json.contains("\"matches\":"));
        assertTrue(json.contains("\"line\": 5"));
        assertTrue(json.contains("\"count\": 2"));
        assertTrue(json.contains("\"snippet\": \"keyword here\""));
    }

    @Test
    public void testFileJsonOutputHasNoUrlOrDepthFields() {
        new ReportWriter().printFileJsonOutput(
                fileOpts("/tmp/t.pdf", "x"), Collections.emptyList(), 50);
        String json = out.toString();
        assertFalse("url field must not appear in file-mode JSON", json.contains("\"url\""));
        assertFalse("depth field must not appear in file-mode JSON", json.contains("\"depth\""));
    }

    @Test
    public void testFileJsonOutputOmitsPageFieldWhenNoPageStructure() {
        List<FileMatch> matches = List.of(new FileMatch(0, 3, 1, "snippet"));
        new ReportWriter().printFileJsonOutput(fileOpts("/tmp/t.txt", "x"), matches, 50);
        assertFalse(out.toString().contains("\"page\""));
    }

    @Test
    public void testFileJsonOutputIncludesPageFieldWhenPresent() {
        List<FileMatch> matches = Arrays.asList(
                new FileMatch(1, 2, 1, "p1 line"),
                new FileMatch(2, 4, 1, "p2 line")
        );
        new ReportWriter().printFileJsonOutput(fileOpts("/tmp/t.pdf", "x"), matches, 50);
        String json = out.toString();
        assertTrue(json.contains("\"page\": 1"));
        assertTrue(json.contains("\"page\": 2"));
    }

    @Test
    public void testFileJsonOutputEmptyMatchesArrayIsValid() {
        new ReportWriter().printFileJsonOutput(
                fileOpts("/tmp/t.txt", "notfound"), Collections.emptyList(), 50);
        String json = out.toString();
        assertTrue(json.contains("\"total_matches\": 0"));
        assertTrue(json.contains("\"matches\":"));
    }

    @Test
    public void testFileJsonOutputEscapesQuotesInSnippet() {
        List<FileMatch> matches = List.of(new FileMatch(0, 1, 1, "fox said \"hello\""));
        new ReportWriter().printFileJsonOutput(fileOpts("/tmp/t.txt", "fox"), matches, 50);
        assertTrue(out.toString().contains("\\\"hello\\\""));
    }

    @Test
    public void testFileJsonOutputTotalMatchesSumsAllLines() {
        List<FileMatch> matches = Arrays.asList(
                new FileMatch(0, 1, 3, "line with three"),
                new FileMatch(0, 2, 2, "line with two"),
                new FileMatch(0, 3, 1, "line with one")
        );
        new ReportWriter().printFileJsonOutput(fileOpts("/tmp/t.txt", "x"), matches, 50);
        assertTrue(out.toString().contains("\"total_matches\": 6"));
    }

    // ── folder mode text output ───────────────────────────────────────────────

    private CliOptions folderOpts(String folder, String keyword) {
        return CliOptions.parse(new String[]{"-F", folder, "-k", keyword});
    }

    private FileScanResult fileResult(String path, FileMatch... matches) {
        return new FileScanResult(path, Arrays.asList(matches));
    }

    @Test
    public void testFolderTextOutputNoMatches() {
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 5, 0, 0, 200);
        String output = out.toString();
        assertTrue(output.contains("--- WebGrep Results ---"));
        assertTrue(output.contains("Folder: /tmp/docs"));
        assertTrue(output.contains("Files scanned: 5"));
        assertTrue(output.contains("With matches: 0"));
        assertTrue(output.contains("Total matches found: 0"));
        assertFalse("File listing must not appear when there are no matches", output.contains("matches)"));
    }

    @Test
    public void testFolderTextOutputSkippedFilesShownWhenNonZero() {
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 3, 2, 0, 100);
        assertTrue(out.toString().contains("Skipped (too large): 2"));
    }

    @Test
    public void testFolderTextOutputSkippedFilesHiddenWhenZero() {
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 3, 0, 0, 100);
        assertFalse(out.toString().contains("Skipped"));
    }

    @Test
    public void testFolderTextOutputWithMultipleFiles() {
        List<FileScanResult> results = Arrays.asList(
                fileResult("/tmp/docs/a.txt", new FileMatch(0, 2, 1, "fox here")),
                fileResult("/tmp/docs/b.pdf",
                        new FileMatch(1, 3, 1, "fox p1"),
                        new FileMatch(2, 1, 2, "fox fox p2"))
        );
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), results, 10, 0, 0, 500);
        String output = out.toString();

        assertTrue(output.contains("Total matches found: 4")); // 1+1+2
        assertTrue(output.contains("With matches: 2"));
        assertTrue(output.contains("/tmp/docs/a.txt  (1 match)"));
        assertTrue(output.contains("/tmp/docs/b.pdf  (3 matches)"));
        assertTrue(output.contains("l.2"));
        assertTrue(output.contains("p.1, l.3"));
        assertTrue(output.contains("p.2, l.1"));
        assertTrue(output.contains("(2 matches)"));
    }

    @Test
    public void testFolderTextOutputNoPageColumnWhenAllPageZero() {
        List<FileScanResult> results = List.of(
                fileResult("/tmp/f.txt", new FileMatch(0, 5, 1, "match"))
        );
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp", "x"), results, 1, 0, 0, 50);
        assertFalse("Page column must not appear when all page=0", out.toString().contains("p."));
    }

    // ── folder mode JSON output ───────────────────────────────────────────────

    @Test
    public void testFolderJsonOutputStructure() {
        List<FileScanResult> results = List.of(
                fileResult("/tmp/docs/a.txt", new FileMatch(0, 2, 3, "triple fox"))
        );
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp/docs", "fox"), results, 7, 1, 0, 300);
        String json = out.toString();

        assertTrue(json.contains("\"folder\": \"/tmp/docs\""));
        assertTrue(json.contains("\"keyword\": \"fox\""));
        assertTrue(json.contains("\"files_scanned\": 7"));
        assertTrue(json.contains("\"files_skipped\": 1"));
        assertTrue(json.contains("\"files_with_matches\": 1"));
        assertTrue(json.contains("\"total_matches\": 3"));
        assertTrue(json.contains("\"results\":"));
        assertTrue(json.contains("\"file\": \"/tmp/docs/a.txt\""));
        assertTrue(json.contains("\"total_matches\": 3"));
        assertTrue(json.contains("\"line\": 2"));
        assertTrue(json.contains("\"count\": 3"));
        assertFalse("url field must not appear in folder-mode JSON", json.contains("\"url\""));
        assertFalse("depth field must not appear in folder-mode JSON", json.contains("\"depth\""));
    }

    @Test
    public void testFolderJsonOutputOmitsPageWhenNotPresent() {
        List<FileScanResult> results = List.of(
                fileResult("/tmp/f.txt", new FileMatch(0, 1, 1, "snippet"))
        );
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp", "x"), results, 1, 0, 0, 50);
        assertFalse(out.toString().contains("\"page\""));
    }

    @Test
    public void testFolderJsonOutputIncludesPageWhenPresent() {
        List<FileScanResult> results = List.of(
                fileResult("/tmp/f.pdf",
                        new FileMatch(1, 2, 1, "p1"),
                        new FileMatch(3, 4, 1, "p3"))
        );
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp", "x"), results, 1, 0, 0, 50);
        String json = out.toString();
        assertTrue(json.contains("\"page\": 1"));
        assertTrue(json.contains("\"page\": 3"));
    }

    @Test
    public void testFolderJsonOutputEmptyResults() {
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp/docs", "x"), Collections.emptyList(), 10, 2, 0, 100);
        String json = out.toString();
        assertTrue(json.contains("\"total_matches\": 0"));
        assertTrue(json.contains("\"files_with_matches\": 0"));
        assertTrue(json.contains("\"results\":"));
    }

    @Test
    public void testFolderTextOutputShowsFailedFilesWhenNonZero() {
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 5, 0, 2, 100);
        String output = out.toString();
        assertTrue(output.contains("Failed (unreadable): 2"));
    }

    @Test
    public void testFolderTextOutputHidesFailedLineWhenZero() {
        new ReportWriter().printFolderTextOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 5, 0, 0, 100);
        assertFalse(out.toString().contains("Failed"));
    }

    @Test
    public void testFolderJsonOutputIncludesFilesFailed() {
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp/docs", "fox"), Collections.emptyList(), 5, 0, 3, 100);
        assertTrue(out.toString().contains("\"files_failed\": 3"));
    }

    @Test
    public void testFolderJsonOutputMultipleFilesOrdered() {
        List<FileScanResult> results = Arrays.asList(
                fileResult("/tmp/a.txt", new FileMatch(0, 1, 2, "aa")),
                fileResult("/tmp/b.txt", new FileMatch(0, 3, 1, "bb"))
        );
        new ReportWriter().printFolderJsonOutput(
                folderOpts("/tmp", "x"), results, 5, 0, 0, 50);
        String json = out.toString();
        int posA = json.indexOf("/tmp/a.txt");
        int posB = json.indexOf("/tmp/b.txt");
        assertTrue("Files must appear in the order provided", posA < posB);
        assertTrue(json.contains("\"total_matches\": 3")); // 2+1
    }
}
