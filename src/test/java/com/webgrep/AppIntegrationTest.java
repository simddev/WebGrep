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

    // ── --folder end-to-end ───────────────────────────────────────────────────

    @Test
    public void testHelpMentionsFolderFlag() {
        Main.main(new String[]{"--help"});
        String output = out();
        assertTrue("Help must document --folder flag", output.contains("--folder"));
        assertTrue("Help must document -F short flag", output.contains("-F"));
    }

    @Test
    public void testFolderModeEndToEndTextOutput() throws Exception {
        File dir = Files.createTempDirectory("webgrep-folder-e2e").toFile();
        dir.deleteOnExit();

        File a = new File(dir, "alpha.txt");
        File b = new File(dir, "beta.txt");
        Files.write(a.toPath(), "no match here\nfox on line two\nanother fox".getBytes());
        Files.write(b.toPath(), "nothing at all".getBytes());

        Main.main(new String[]{"-F", dir.getAbsolutePath(), "-k", "fox"});
        String output = out();

        assertTrue(output.contains("--- WebGrep Results ---"));
        assertTrue(output.contains("Folder: " + dir.getAbsolutePath()));
        assertTrue(output.contains("Files scanned: 2"));
        assertTrue(output.contains("With matches: 1"));
        assertTrue(output.contains("Total matches found: 2"));
        assertTrue(output.contains("alpha.txt"));
        assertTrue(output.contains("l.2"));
        assertTrue(output.contains("l.3"));
        assertFalse("beta.txt has no matches — must not appear", output.contains("beta.txt"));
    }

    @Test
    public void testFolderModeEndToEndRecursive() throws Exception {
        File dir = Files.createTempDirectory("webgrep-recursive-e2e").toFile();
        dir.deleteOnExit();
        File sub = new File(dir, "sub");
        sub.mkdir();

        Files.write(new File(dir, "root.txt").toPath(), "fox in root".getBytes());
        Files.write(new File(sub, "nested.txt").toPath(), "fox in sub".getBytes());

        Main.main(new String[]{"-F", dir.getAbsolutePath(), "-k", "fox"});
        String output = out();

        assertTrue(output.contains("Files scanned: 2"));
        assertTrue(output.contains("With matches: 2"));
        assertTrue(output.contains("root.txt"));
        assertTrue(output.contains("nested.txt"));
    }

    @Test
    public void testFolderModeEndToEndNoMatches() throws Exception {
        File dir = Files.createTempDirectory("webgrep-nomatch-e2e").toFile();
        dir.deleteOnExit();
        Files.write(new File(dir, "f.txt").toPath(), "no match here at all".getBytes());

        Main.main(new String[]{"-F", dir.getAbsolutePath(), "-k", "elephant"});
        String output = out();

        assertTrue(output.contains("Total matches found: 0"));
        assertTrue(output.contains("With matches: 0"));
    }

    @Test
    public void testFolderModeEndToEndJsonOutput() throws Exception {
        File dir = Files.createTempDirectory("webgrep-json-folder-e2e").toFile();
        dir.deleteOnExit();
        Files.write(new File(dir, "doc.txt").toPath(), "fox on line one\nno match".getBytes());

        Main.main(new String[]{"-F", dir.getAbsolutePath(), "-k", "fox", "-o", "json"});
        String json = out();

        assertTrue(json.contains("\"folder\":"));
        assertTrue(json.contains("\"files_scanned\": 1"));
        assertTrue(json.contains("\"files_with_matches\": 1"));
        assertTrue(json.contains("\"total_matches\": 1"));
        assertTrue(json.contains("\"results\":"));
        assertTrue(json.contains("\"line\": 1"));
        assertFalse("url field must not appear in folder-mode JSON", json.contains("\"url\""));
    }

    @Test
    public void testFolderModeSkipsFilesOverMaxBytes() throws Exception {
        File dir = Files.createTempDirectory("webgrep-size-e2e").toFile();
        dir.deleteOnExit();
        // Write a file larger than the 10-byte limit we set
        Files.write(new File(dir, "big.txt").toPath(), "fox fox fox fox fox fox fox".getBytes());

        Main.main(new String[]{"-F", dir.getAbsolutePath(), "-k", "fox", "-b", "5"});
        String output = out();

        assertTrue(output.contains("Skipped (too large): 1"));
        assertTrue(output.contains("Total matches found: 0"));
    }
}
