package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.ContentExtractor;
import com.webgrep.core.Crawler;
import com.webgrep.core.MatchEngine;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.FileMatch;
import com.webgrep.reporting.FileScanResult;
import com.webgrep.reporting.ReportWriter;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * WebGrep - Keyword search across websites, local files, and folders.
 *
 * <p>Entry point for the application. Parses command-line arguments via {@link CliOptions},
 * then routes execution to one of three modes:
 * <ul>
 *   <li><b>Web crawl</b> - {@link Crawler} fetches pages and documents starting from a seed URL.</li>
 *   <li><b>Local file</b> - {@link #scanLocalFile} reads and searches a single file on disk.</li>
 *   <li><b>Local folder</b> - {@link #scanFolder} recursively searches all files in a directory.</li>
 * </ul>
 *
 * <p>All three modes share {@link ContentExtractor} (for text extraction) and {@link MatchEngine}
 * (for keyword matching), and pass results to {@link ReportWriter} for text or JSON output.
 *
 * @author Simon D.
 */
public class Main {

    /**
     * Application entry point.
     *
     * <p>Execution order:
     * <ol>
     *   <li>{@link #setupLogging()} silences all third-party library logging.</li>
     *   <li>{@link CliOptions#parse(String[])} parses the raw arguments.</li>
     *   <li>If {@code --help} or no arguments: print help and exit.</li>
     *   <li>If {@code --install-browser}: install the browser and exit.</li>
     *   <li>Otherwise: validate options, build shared components, and run the appropriate mode.</li>
     * </ol>
     *
     * <p>Exit codes: {@code 0} = success; {@code 1} = configuration error;
     * {@code 2} = fatal runtime error.
     *
     * @param args raw command-line arguments.
     */
    public static void main(String[] args) {
        setupLogging();

        try {
            CliOptions options = CliOptions.parse(args);

            if (options.isHelp() || args.length == 0) {
                CliOptions.printHelp();
                return;
            }

            if (options.isInstallBrowser()) {
                installBrowser(options);
                return;
            }

            options.validate();

            ContentExtractor extractor = new ContentExtractor(options.getMaxBytes());
            MatchEngine matchEngine = new MatchEngine();
            ReportWriter reportWriter = new ReportWriter();
            long startTime = System.currentTimeMillis();

            if (options.getFolder() != null) {
                FolderScan scan = scanFolder(options, extractor, matchEngine);
                long durationMs = System.currentTimeMillis() - startTime;
                if ("json".equals(options.getOutput())) {
                    reportWriter.printFolderJsonOutput(options, scan.results(), scan.scanned(), scan.skipped(), scan.failed(), scan.stoppedAtMaxHits(), durationMs);
                } else {
                    reportWriter.printFolderTextOutput(options, scan.results(), scan.scanned(), scan.skipped(), scan.failed(), scan.stoppedAtMaxHits(), durationMs);
                }
            } else if (options.getFile() != null) {
                List<FileMatch> fileMatches = scanLocalFile(options, extractor, matchEngine);
                long durationMs = System.currentTimeMillis() - startTime;
                if ("json".equals(options.getOutput())) {
                    reportWriter.printFileJsonOutput(options, fileMatches, durationMs);
                } else {
                    reportWriter.printFileTextOutput(options, fileMatches, durationMs);
                }
            } else {
                Crawler crawler = new Crawler(options, extractor, matchEngine);
                CrawlResult result = crawler.crawl();
                result.durationMs = System.currentTimeMillis() - startTime;
                if ("json".equals(options.getOutput())) {
                    reportWriter.printJsonOutput(result, options);
                } else {
                    reportWriter.printTextOutput(result);
                }
            }

        } catch (IllegalArgumentException e) {
            System.err.println("Configuration Error: " + e.getMessage());
            System.err.println("Use -h or --help for usage information.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal Error: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " - " + e.getMessage() : ""));
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("  Caused by: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() != null ? " - " + cause.getMessage() : ""));
                cause = cause.getCause();
            }
            System.exit(2);
        }
    }

    /**
     * Carries the results of a completed folder scan back to {@link #main}.
     *
     * @param results         list of files that contained at least one keyword match.
     * @param scanned         number of files that were read and searched.
     * @param skipped         number of files skipped because they exceeded {@code --max-bytes}.
     * @param failed          number of files that could not be read or parsed.
     * @param stoppedAtMaxHits the {@code --max-hits} value that triggered an early stop, or 0.
     */
    private record FolderScan(List<FileScanResult> results, int scanned, int skipped, int failed, int stoppedAtMaxHits) {}

    /**
     * Recursively scans all regular files in the directory specified by {@code options.getFolder()},
     * searching each for the keyword and collecting matching lines.
     *
     * <p>Files are processed in sorted path order. Each file is:
     * <ol>
     *   <li>Skipped (counted as {@code skipped}) if it exceeds {@code --max-bytes}.</li>
     *   <li>Read in full via {@link Files#readAllBytes}.</li>
     *   <li>Passed to {@link ContentExtractor#extractTextFromBinary} for Tika parsing.</li>
     *   <li>Searched with {@link #findLineMatches}.</li>
     * </ol>
     *
     * <p>A progress indicator is printed to stderr during the scan and erased on completion.
     * The scan stops early if {@code --max-hits} is reached mid-file (the check fires after
     * each complete file, so the actual total may slightly exceed the limit).
     *
     * @param options     validated CLI options.
     * @param extractor   shared text extractor.
     * @param matchEngine shared keyword matcher.
     * @return a {@link FolderScan} record with results and statistics.
     * @throws Exception if the directory cannot be walked (permissions, I/O error).
     */
    private static FolderScan scanFolder(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        File dir = new File(options.getFolder());
        if (!dir.exists() || !dir.isDirectory())
            throw new IllegalArgumentException("Folder not found: " + options.getFolder());

        List<Path> files;
        try (var stream = Files.walk(dir.toPath())) {
            files = stream
                    .filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p))
                    .sorted().toList();
        }

        List<FileScanResult> results = new ArrayList<>();
        int scanned = 0, skipped = 0, failed = 0, totalMatches = 0;
        boolean stoppedEarly = false;

        for (Path path : files) {
            if (path.toFile().length() > options.getMaxBytes()) {
                skipped++;
                continue;
            }
            scanned++;
            System.err.printf("\r  Scanning (%d/%d): %-80s", scanned + skipped, files.size(), path.getFileName());
            try {
                byte[] bytes = Files.readAllBytes(path);
                String text = extractor.extractTextFromBinary(bytes, path.getFileName().toString(), null);
                List<FileMatch> matches = findLineMatches(text, options.getKeyword(), options.getMode(), matchEngine);
                if (!matches.isEmpty()) {
                    results.add(new FileScanResult(path.toString(), matches));
                    totalMatches += matches.stream().mapToInt(FileMatch::count).sum();
                }
            } catch (Exception e) {
                failed++;
            }
            if (options.getMaxHits() > 0 && totalMatches >= options.getMaxHits()) {
                stoppedEarly = true;
                break;
            }
        }
        System.err.print("\r" + " ".repeat(100) + "\r");
        return new FolderScan(results, scanned, skipped, failed, stoppedEarly ? options.getMaxHits() : 0);
    }

    /**
     * Reads and searches a single local file specified by {@code options.getFile()}.
     *
     * <p>If the file exceeds {@code --max-bytes}, a warning is printed to stderr and an empty
     * match list is returned (the file is not searched).
     *
     * @param options     validated CLI options.
     * @param extractor   shared text extractor.
     * @param matchEngine shared keyword matcher.
     * @return list of matching lines; empty if no matches or the file was skipped.
     * @throws Exception if the file cannot be read.
     */
    private static List<FileMatch> scanLocalFile(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        File f = new File(options.getFile());
        if (!f.exists() || !f.isFile())
            throw new IllegalArgumentException("File not found: " + options.getFile());
        if (f.length() > options.getMaxBytes()) {
            System.err.println("Warning: file exceeds --max-bytes limit, skipping: " + options.getFile());
            return List.of();
        }

        byte[] bytes = Files.readAllBytes(f.toPath());
        String text = extractor.extractTextFromBinary(bytes, f.getName(), null);
        return findLineMatches(text, options.getKeyword(), options.getMode(), matchEngine);
    }

    /**
     * Searches the extracted plain text for the keyword, returning one {@link FileMatch} per
     * matching line.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Normalise line endings: {@code \r\n} and bare {@code \r} are both mapped to {@code \n}.</li>
     *   <li>Detect page boundaries: Apache Tika inserts {@code \f} (form-feed, U+000C) as a page
     *       separator in multi-page documents such as PDFs. If {@code \f} is present, the text
     *       is split by page and the {@code page} field is set to the 1-based page number;
     *       otherwise {@code page = 0} (no page structure).</li>
     *   <li>For each line on each page, {@link MatchEngine#countMatches} is called. Lines with
     *       at least one match are recorded as {@link FileMatch} entries.</li>
     *   <li>Snippets are trimmed and truncated to 120 characters.</li>
     * </ol>
     *
     * <p>Package-private (not private) so that unit tests can call it directly without going
     * through the full {@code main} stack.
     *
     * @param text        the plain text to search; may be {@code null} or empty.
     * @param keyword     the keyword to search for.
     * @param mode        matching mode: {@code "default"}, {@code "exact"}, or {@code "fuzzy"}.
     * @param matchEngine the configured {@link MatchEngine} instance.
     * @return a list of matching lines; empty if no matches are found.
     */
    static List<FileMatch> findLineMatches(String text, String keyword, String mode, MatchEngine matchEngine) {
        List<FileMatch> matches = new ArrayList<>();
        if (text == null || text.isEmpty()) return matches;

        // Tika uses \f (form feed) as page separator for multi-page documents (e.g. PDF)
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        boolean hasPages = normalized.contains("\f");
        String[] pages = normalized.split("\f", -1);

        for (int p = 0; p < pages.length; p++) {
            String[] lines = pages[p].split("\n", -1);
            for (int l = 0; l < lines.length; l++) {
                int count = matchEngine.countMatches(lines[l], keyword, mode);
                if (count > 0) {
                    String snippet = lines[l].trim();
                    if (snippet.length() > 120) snippet = snippet.substring(0, 117) + "...";
                    matches.add(new FileMatch(hasPages ? p + 1 : 0, l + 1, count, snippet));
                }
            }
        }
        return matches;
    }

    /**
     * Handles the {@code --install-browser} flag.
     *
     * <p>Checks for a usable browser in the following order, skipping download if one is found:
     * <ol>
     *   <li>System Chromium/Chrome - always reliable (speaks CDP natively).</li>
     *   <li>Playwright's cached Firefox - previously downloaded, always compatible.</li>
     *   <li>Playwright's cached Chromium - previously downloaded, always compatible.</li>
     *   <li>System Firefox stable - trusted unless the path indicates Developer Edition
     *       (hyphen-separated on Linux, space-separated on macOS/Windows) or Nightly, which
     *       are incompatible with Playwright's patched protocol.</li>
     * </ol>
     *
     * <p>If no compatible browser is found, prompts the user to choose (or respects the
     * {@code --browser} flag) and calls the Playwright CLI installer.
     *
     * @param options validated CLI options; {@code options.getBrowser()} controls the preference.
     * @throws Exception if the Playwright installer throws.
     */
    private static void installBrowser(CliOptions options) throws Exception {
        String pref = options.getBrowser(); // "auto", "firefox", or "chromium"

        // Chromium/Chrome speaks CDP natively - always compatible with Playwright.
        java.util.Optional<java.nio.file.Path> sysChr = com.webgrep.core.BrowserFinder.findChromium();
        if (sysChr.isPresent() && !pref.equals("firefox")) {
            System.out.println("System Chromium/Chrome already available at: " + sysChr.get());
            System.out.println("WebGrep will use it automatically - no installation needed.");
            return;
        }

        // Playwright's own cached builds are always compatible.
        java.nio.file.Path pwCache = java.nio.file.Paths.get(
                System.getProperty("user.home"), ".cache", "ms-playwright");
        if (!pref.equals("chromium") && isCachedBrowser(pwCache, "firefox-")) {
            System.out.println("Playwright Firefox is already installed in the cache.");
            System.out.println("WebGrep will use it automatically - no installation needed.");
            return;
        }
        if (!pref.equals("firefox") && isCachedBrowser(pwCache, "chromium-")) {
            System.out.println("Playwright Chromium is already installed in the cache.");
            System.out.println("WebGrep will use it automatically - no installation needed.");
            return;
        }

        // System Firefox stable is usable by Playwright. Developer Edition and Nightly are
        // incompatible with Playwright's patched protocol and must not be reported as ready.
        java.util.Optional<java.nio.file.Path> sysFf = com.webgrep.core.BrowserFinder.findFirefox();
        if (sysFf.isPresent() && !pref.equals("chromium")) {
            String p = sysFf.get().toString().toLowerCase();
            // "developer-edition" matches Linux paths; "developer edition" (with space) matches
            // macOS ("Firefox Developer Edition.app") and Windows ("Firefox Developer Edition\").
            boolean incompatible = p.contains("developer-edition") || p.contains("developer edition")
                    || p.contains("nightly");
            if (!incompatible) {
                System.out.println("System Firefox already available at: " + sysFf.get());
                System.out.println("WebGrep will use it automatically - no installation needed.");
                return;
            }
        }

        // Decide what to download.
        String toInstall;
        if (pref.equals("firefox") || pref.equals("chromium")) {
            toInstall = pref;
        } else {
            // Auto: prompt
            System.out.println("No compatible browser found. Choose a browser to install:");
            System.out.println("  [1] Firefox  (Mozilla Foundation, ~105 MB)  [default]");
            System.out.println("  [2] Chromium (Google, ~120 MB)");
            System.out.print("Enter choice [1/2] or Enter for Firefox: ");
            java.io.Console console = System.console();
            String line = console != null ? console.readLine() : null;
            toInstall = (line != null && line.trim().equals("2")) ? "chromium" : "firefox";
        }

        String label = toInstall.equals("chromium") ? "Chromium (Google)" : "Firefox (Mozilla)";
        System.out.println("Installing Playwright " + label + "...");
        com.microsoft.playwright.CLI.main(new String[]{"install", toInstall});
        System.out.println("Done. WebGrep will use it automatically for SPA pages.");
    }

    /**
     * Returns {@code true} if the Playwright browser cache directory contains a subdirectory
     * whose name starts with {@code prefix} (e.g. {@code "firefox-"} or {@code "chromium-"}).
     *
     * @param cache  path to {@code ~/.cache/ms-playwright}.
     * @param prefix the directory name prefix to search for.
     * @return {@code true} if a matching cached browser directory exists.
     */
    private static boolean isCachedBrowser(java.nio.file.Path cache, String prefix) {
        try {
            if (!java.nio.file.Files.isDirectory(cache)) return false;
            try (var entries = java.nio.file.Files.list(cache)) {
                return entries.anyMatch(p -> p.getFileName().toString().startsWith(prefix));
            }
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Suppresses all third-party library logging so that only WebGrep's own output reaches
     * the user.
     *
     * <p>Libraries silenced: Apache Commons Logging (used by Tika), SLF4J Simple (used by
     * Playwright), and the JUL (Java Util Logging) root logger used by various Tika parsers.
     * All three must be silenced independently because they use different logging frameworks.
     */
    private static void setupLogging() {
        // Suppress noisy library logging
        System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        try {
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.OFF);
        } catch (Exception ignored) {}
    }
}
