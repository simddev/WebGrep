package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.ReportWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

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
}
