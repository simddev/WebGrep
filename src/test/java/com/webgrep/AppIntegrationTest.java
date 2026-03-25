package com.webgrep;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class AppIntegrationTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private PrintStream origOut;

    @Before
    public void redirectStdout() {
        origOut = System.out;
        System.setOut(new PrintStream(outBuf));
    }

    @After
    public void restoreStdout() {
        System.setOut(origOut);
    }

    private String out() { return outBuf.toString(); }

    // ── help ─────────────────────────────────────────────────────────────────

    @Test
    public void testHelpOutput() {
        Main.main(new String[]{"--help"});
        String output = out();
        assertTrue(output.contains("Usage: java -jar WebGrep.jar"));
        assertTrue(output.contains("--url"));
        assertTrue(output.contains("--keyword"));
    }

    @Test
    public void testHelpShortFlag() {
        Main.main(new String[]{"-h"});
        assertTrue(out().contains("Usage: java -jar WebGrep.jar"));
    }

    @Test
    public void testNoArgsShowsHelp() {
        Main.main(new String[]{});
        assertTrue(out().contains("Usage: java -jar WebGrep.jar"));
    }

    @Test
    public void testHelpMentionsFileFlag() {
        Main.main(new String[]{"--help"});
        String output = out();
        assertTrue("Help must document --file flag", output.contains("--file"));
        assertTrue("Help must document -f short flag", output.contains("-f"));
    }

    // ── --file end-to-end ─────────────────────────────────────────────────────

    @Test
    public void testFileModeEndToEndTextOutput() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(),
                "no match here\nThe quick brown fox\nanother fox here\nnothing".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "fox"});
        String output = out();

        assertTrue(output.contains("--- WebGrep Results ---"));
        assertTrue(output.contains("File: " + tmp.getAbsolutePath()));
        assertTrue(output.contains("Total matches found: 2"));
        assertTrue(output.contains("Matches:"));
        assertTrue(output.contains("l.2"));
        assertTrue(output.contains("l.3"));
        assertTrue(output.contains("The quick brown fox"));
        assertTrue(output.contains("another fox here"));
    }

    @Test
    public void testFileModeEndToEndNoMatches() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e-nomatch", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "absolutely nothing useful here".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "elephant"});
        String output = out();

        assertTrue(output.contains("Total matches found: 0"));
        assertFalse(output.contains("Matches:"));
    }

    @Test
    public void testFileModeEndToEndJsonOutput() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e-json", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "fox on line one\nno match\nfox on line three".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "fox", "-o", "json"});
        String json = out();

        assertTrue(json.contains("\"file\":"));
        assertTrue(json.contains("\"keyword\": \"fox\""));
        assertTrue(json.contains("\"total_matches\": 2"));
        assertTrue(json.contains("\"matches\":"));
        assertTrue(json.contains("\"line\": 1"));
        assertTrue(json.contains("\"line\": 3"));
        assertFalse("url field must not appear in file-mode JSON", json.contains("\"url\""));
    }

    @Test
    public void testFileModeExactModeMatchesCorrectly() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e-exact", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "Fox is here\nfox is lowercase\nFOX is upper".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "fox", "-m", "exact"});
        // exact mode is case-sensitive: only the lowercase "fox" line matches
        String output = out();
        assertTrue(output.contains("Total matches found: 1"));
        assertTrue(output.contains("l.2"));
    }

    @Test
    public void testFileModeWithWindowsLineEndings() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e-crlf", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "line one\r\nfox here\r\nline three\r\n".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "fox"});
        String output = out();

        assertTrue(output.contains("Total matches found: 1"));
        // The match must land on line 2, not be mis-counted due to \r
        assertTrue(output.contains("l.2"));
        assertTrue(output.contains("fox here"));
    }

    @Test
    public void testFileModeMultipleMatchesPerLine() throws Exception {
        File tmp = File.createTempFile("webgrep-e2e-multi", ".txt");
        tmp.deleteOnExit();
        Files.write(tmp.toPath(), "fox and fox and fox on one line".getBytes());

        Main.main(new String[]{"-f", tmp.getAbsolutePath(), "-k", "fox"});
        String output = out();

        assertTrue(output.contains("Total matches found: 3"));
        assertTrue(output.contains("(3 matches)"));
    }
}
