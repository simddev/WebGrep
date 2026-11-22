package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.ContentExtractor;
import com.webgrep.core.Crawler;
import com.webgrep.core.MatchEngine;
import com.webgrep.core.PlaywrightRenderer;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.FileMatch;
import com.webgrep.reporting.FileScanResult;
import com.webgrep.reporting.ReportWriter;
import com.webgrep.utils.ConsoleText;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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

    /** Longest snippet shown for a match in file and folder results. */
    private static final int MAX_LINE_SNIPPET = 120;

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
     * {@code 2} = fatal runtime error, including an {@code --install-browser} run that ends
     * without a usable browser.
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
                // A failed installation must fail the command, so that a script preparing a
                // machine for SPA crawls stops there instead of carrying on without a browser.
                if (!installBrowser(options)) System.exit(2);
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
            System.err.println("Configuration Error: " + ConsoleText.safe(e.getMessage()));
            System.err.println("Use -h or --help for usage information.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fatal Error: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? " - " + ConsoleText.safe(e.getMessage()) : ""));
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("  Caused by: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() != null ? " - " + ConsoleText.safe(cause.getMessage()) : ""));
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
     * <p>Files are listed by {@link #listFiles} and processed in sorted path order. Each file is:
     * <ol>
     *   <li>Skipped (counted as {@code skipped}) if it exceeds {@code --max-bytes}.</li>
     *   <li>Read in full via {@link Files#readAllBytes}.</li>
     *   <li>Passed to {@link ContentExtractor#extractTextFromBinary} for Tika parsing.</li>
     *   <li>Searched with {@link #findLineMatches}.</li>
     * </ol>
     *
     * <p>A progress indicator is printed to stderr during the scan and erased on completion.
     * The scan stops early if {@code --max-hits} is reached (the check fires after each complete
     * file, so the actual total may slightly exceed the limit).
     *
     * @param options     validated CLI options.
     * @param extractor   shared text extractor.
     * @param matchEngine shared keyword matcher.
     * @return a {@link FolderScan} record with results and statistics.
     * @throws Exception if the directory cannot be walked (permissions, I/O error).
     */
    private static FolderScan scanFolder(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        Path root = Path.of(options.getFolder());
        if (!Files.isDirectory(root))
            throw new IllegalArgumentException("Folder not found: " + options.getFolder());

        List<Path> files = listFiles(root);
        List<FileScanResult> results = new ArrayList<>();
        int scanned = 0, skipped = 0, failed = 0, totalMatches = 0;
        boolean stoppedEarly = false;

        for (Path path : files) {
            if (path.toFile().length() > options.getMaxBytes()) {
                skipped++;
                continue;
            }
            scanned++;
            System.err.printf("\r  Scanning (%d/%d): %-80s", scanned + skipped, files.size(),
                    ConsoleText.safe(path.getFileName().toString()));
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
     * Lists the regular files under {@code root}, recursively, in sorted order.
     *
     * <p>Only regular files are returned. Symbolic links are skipped, so a file reachable
     * through a link is never searched twice and a link cycle cannot trap the walk. Named pipes,
     * sockets and device files are skipped too: reading a pipe blocks until something writes to
     * it, which would hang the scan, and a device such as {@code /dev/zero} never ends.
     * Unreadable subdirectories are skipped rather than aborting the scan.
     *
     * <p>{@code root} itself may be a symbolic link to a folder. The walk does not follow links,
     * so it starts from the link's target; the returned paths are still expressed under
     * {@code root} as the user typed it.
     *
     * <p>Package-private so that tests can exercise the real traversal.
     *
     * @param root the folder to list.
     * @return the regular files below {@code root}, sorted by path.
     * @throws IOException if {@code root} cannot be resolved or walked.
     */
    static List<Path> listFiles(Path root) throws IOException {
        Path walkRoot = root.toRealPath();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(walkRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                // The attributes describe the entry itself, not a link target, so a symbolic
                // link is never a regular file here.
                if (a.isRegularFile()) files.add(root.resolve(walkRoot.relativize(f)));
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(null);
        return files;
    }

    /**
     * Reads and searches a single local file specified by {@code options.getFile()}.
     *
     * <p>If the file exceeds {@code --max-bytes}, a warning is printed to stderr and an empty
     * match list is returned (the file is not searched). A path that names a folder, a named
     * pipe or another special file is rejected with a configuration error rather than read.
     *
     * @param options     validated CLI options.
     * @param extractor   shared text extractor.
     * @param matchEngine shared keyword matcher.
     * @return list of matching lines; empty if no matches or the file was skipped.
     * @throws Exception if the file cannot be read.
     */
    private static List<FileMatch> scanLocalFile(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        Path path = Path.of(options.getFile());
        if (!Files.exists(path))
            throw new IllegalArgumentException("File not found: " + options.getFile());
        if (Files.isDirectory(path))
            throw new IllegalArgumentException("Not a file but a folder (search folders with -F): " + options.getFile());
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Not a regular file: " + options.getFile());
        if (Files.size(path) > options.getMaxBytes()) {
            System.err.println("Warning: file exceeds --max-bytes limit, skipping: " + ConsoleText.safe(options.getFile()));
            return List.of();
        }

        byte[] bytes = Files.readAllBytes(path);
        String text = extractor.extractTextFromBinary(bytes, path.getFileName().toString(), null);
        return findLineMatches(text, options.getKeyword(), options.getMode(), matchEngine);
    }

    /**
     * Searches extracted plain text for the keyword, returning one {@link FileMatch} per line on
     * which at least one match starts.
     *
     * <p>Processing steps:
     * <ol>
     *   <li>Normalise line endings: {@code \r\n} and bare {@code \r} are both mapped to {@code \n}.</li>
     *   <li>Split into pages: {@link ContentExtractor} marks the pages of a PDF with form feeds
     *       ({@code \f}, U+000C). If one is present, each match gets the 1-based page number;
     *       otherwise {@code page = 0} (no page structure).</li>
     *   <li>Search each page as a whole, so a phrase broken across lines - as PDF text is at the
     *       end of every printed line - still matches. Each match is reported on the line where
     *       it starts.</li>
     *   <li>Number the lines. In plain text every line counts, as in grep. In paged documents
     *       only non-blank lines count: the blank lines in extracted PDF text are layout
     *       artifacts, so counting them would point past the line the user sees on the page.</li>
     *   <li>Build the snippet: the matching line (joined with the next one when the match
     *       continues there), cut to 120 characters around the match when longer.</li>
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

        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        boolean hasPages = normalized.indexOf('\f') >= 0;
        String[] pages = normalized.split("\f", -1);

        for (int p = 0; p < pages.length; p++) {
            String page = pages[p];
            List<MatchEngine.Match> found = pageMatches(page, keyword, mode, matchEngine);
            if (found.isEmpty()) continue;

            Lines lines = new Lines(page);
            int i = 0;
            while (i < found.size()) {
                MatchEngine.Match first = found.get(i);
                int line = lines.indexOf(anchor(page, first));
                int j = i + 1;
                while (j < found.size() && lines.indexOf(anchor(page, found.get(j))) == line) j++;
                int number = hasPages ? lines.nonBlankNumber(line) : line + 1;
                matches.add(new FileMatch(hasPages ? p + 1 : 0, number, j - i, lines.snippet(line, first)));
                i = j;
            }
        }
        return matches;
    }

    /**
     * Finds the matches on one page, in text order.
     *
     * <p>Fuzzy mode falls back to typo matching only where nothing closer is found. Local scans
     * have always made that decision line by line, so a line keeps its typo matches even when
     * another line of the same page has a correctly spelled one. Close matches are still found
     * across the whole page, so a phrase broken across lines is found in fuzzy mode too.
     *
     * @param page    the page text, lines separated by {@code \n}.
     * @param keyword the keyword.
     * @param mode    the matching mode.
     * @param engine  the matcher.
     * @return the matches, sorted by start offset.
     */
    private static List<MatchEngine.Match> pageMatches(String page, String keyword, String mode, MatchEngine engine) {
        if (!mode.equals("fuzzy")) return engine.findMatches(page, keyword, mode);

        List<MatchEngine.Match> close = engine.findCloseMatches(page, keyword);
        List<MatchEngine.Match> all = new ArrayList<>(close);
        int next = 0;
        int lineStart = 0;
        while (lineStart <= page.length()) {
            int lineEnd = page.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = page.length();
            while (next < close.size() && close.get(next).end() <= lineStart) next++;
            boolean touched = next < close.size() && close.get(next).start() <= lineEnd;
            if (!touched && lineEnd > lineStart) {
                for (MatchEngine.Match m : engine.findTypoMatches(page.substring(lineStart, lineEnd), keyword)) {
                    all.add(new MatchEngine.Match(m.start() + lineStart, m.end() + lineStart));
                }
            }
            lineStart = lineEnd + 1;
        }
        all.sort(Comparator.comparingInt(MatchEngine.Match::start));
        return all;
    }

    /**
     * Returns the offset that decides which line a match belongs to: its first non-whitespace
     * character. A keyword with leading whitespace can make a match start in the line break
     * before the text the user would recognise as the match.
     *
     * @param page  the page text.
     * @param match a match within it.
     * @return the offset of the match's first non-whitespace character.
     */
    private static int anchor(String page, MatchEngine.Match match) {
        for (int k = match.start(); k < match.end(); k++) {
            if (!Character.isWhitespace(page.charAt(k))) return k;
        }
        return match.start();
    }

    /**
     * Line structure of one page: where each line starts, how lines are numbered, and how a
     * line is turned into a snippet.
     */
    private static final class Lines {

        /** The page text. */
        private final String page;

        /** Start offset of every line, in increasing order. */
        private final int[] starts;

        /** For each line, the number of non-blank lines up to and including it. */
        private int[] nonBlankUpTo;

        /**
         * Indexes the line starts of a page.
         *
         * @param page the page text, lines separated by {@code \n}.
         */
        Lines(String page) {
            this.page = page;
            List<Integer> s = new ArrayList<>();
            s.add(0);
            for (int i = page.indexOf('\n'); i >= 0; i = page.indexOf('\n', i + 1)) s.add(i + 1);
            starts = s.stream().mapToInt(Integer::intValue).toArray();
        }

        /**
         * Finds the line containing an offset.
         *
         * @param offset an offset within the page.
         * @return the 0-based index of the line containing it.
         */
        int indexOf(int offset) {
            int i = Arrays.binarySearch(starts, offset);
            return i >= 0 ? i : -i - 2;
        }

        /**
         * Finds where a line ends.
         *
         * @param line a 0-based line index.
         * @return the end offset of that line, excluding its line break.
         */
        int end(int line) {
            return line + 1 < starts.length ? starts[line + 1] - 1 : page.length();
        }

        /**
         * Numbers a line among the page's non-blank lines.
         *
         * @param line a 0-based line index.
         * @return the 1-based position of that line among the non-blank lines.
         */
        int nonBlankNumber(int line) {
            if (nonBlankUpTo == null) {
                nonBlankUpTo = new int[starts.length];
                int count = 0;
                for (int k = 0; k < starts.length; k++) {
                    if (!page.substring(starts[k], end(k)).isBlank()) count++;
                    nonBlankUpTo[k] = count;
                }
            }
            return Math.max(1, nonBlankUpTo[line]);
        }

        /**
         * Builds the snippet for a match starting on line {@code line}: the line's text, extended
         * through every line the match continues onto, with line breaks and tabs shown as spaces
         * and other control characters removed. Longer than 120 characters, it is cut around the
         * match.
         *
         * @param line  the 0-based index of the line the match starts on.
         * @param match the first match on that line.
         * @return the snippet.
         */
        String snippet(int line, MatchEngine.Match match) {
            int from = starts[line];
            int lastLine = Math.max(line, indexOf(Math.max(match.start(), match.end() - 1)));
            int to = end(lastLine);
            StringBuilder sb = new StringBuilder(to - from);
            int matchStart = 0, matchEnd = -1;
            for (int k = from; k < to; k++) {
                if (k == match.start()) matchStart = sb.length();
                if (k == match.end()) matchEnd = sb.length();
                char c = page.charAt(k);
                if (c == '\n' || c == '\t') {
                    sb.append(' ');
                } else if (!ConsoleText.isControl(c)) {
                    sb.append(c);
                }
            }
            if (matchEnd < 0) matchEnd = sb.length();

            int lead = 0, trail = sb.length();
            while (lead < trail && sb.charAt(lead) == ' ') lead++;
            while (trail > lead && sb.charAt(trail - 1) == ' ') trail--;
            String text = sb.substring(lead, trail);
            int start = Math.min(Math.max(matchStart - lead, 0), text.length());
            int end = Math.min(Math.max(matchEnd - lead, start), text.length());
            return fitAround(text, start, end, MAX_LINE_SNIPPET);
        }
    }

    /**
     * Shortens {@code text} to at most {@code max} characters while keeping the range
     * [{@code start}, {@code end}) in view, marking each cut side with {@code ...}.
     *
     * <p>When the range lies within the first {@code max - 3} characters, the text is cut at the
     * end only, as it always was; only matches further along the line move the window.
     *
     * @param text  the text to shorten.
     * @param start start of the range to keep visible.
     * @param end   end of the range to keep visible.
     * @param max   the maximum length of the result.
     * @return {@code text} itself if short enough, otherwise a shortened copy.
     */
    static String fitAround(String text, int start, int end, int max) {
        if (text.length() <= max) return text;
        int keep = max - 3;
        if (end <= keep) return text.substring(0, pairSafeEnd(text, keep)) + "...";
        if (start >= text.length() - keep) {
            return "..." + text.substring(pairSafeStart(text, text.length() - keep));
        }
        int room = max - 6;
        int from = start - Math.max(0, (room - (end - start)) / 2);
        from = Math.max(0, Math.min(from, text.length() - room));
        return "..." + text.substring(pairSafeStart(text, from), pairSafeEnd(text, from + room)) + "...";
    }

    /**
     * Moves a cut start forward past an orphaned low surrogate.
     *
     * @param s the text being cut.
     * @param i a proposed start index.
     * @return the adjusted start index.
     */
    private static int pairSafeStart(String s, int i) {
        return i > 0 && i < s.length() && Character.isLowSurrogate(s.charAt(i)) ? i + 1 : i;
    }

    /**
     * Moves a cut end back before a high surrogate whose partner would be cut off.
     *
     * @param s the text being cut.
     * @param i a proposed end index (exclusive).
     * @return the adjusted end index.
     */
    private static int pairSafeEnd(String s, int i) {
        return i > 0 && i < s.length() && Character.isHighSurrogate(s.charAt(i - 1)) ? i - 1 : i;
    }

    /**
     * Handles the {@code --install-browser} flag.
     *
     * <p>First checks whether a browser WebGrep can use is already present, in the order the
     * renderer tries them: system Chromium/Chrome, then Playwright's Firefox, then Playwright's
     * Chromium (each skipped when {@code --browser} names the other). A browser counts as present
     * only if it actually starts - a cached build left behind by another Playwright version
     * would not.
     *
     * <p>If none starts, the browser named by {@code --browser} is installed, or the user is
     * asked to choose (Firefox by default). Installation goes into WebGrep's own browser folder,
     * {@link PlaywrightRenderer#browsersPath()}, where other Playwright installations cannot
     * delete it.
     *
     * @param options parsed CLI options; {@code options.getBrowser()} controls the preference.
     * @return {@code true} if a usable browser is available at the end; {@code false} if the
     *         installation failed or the installed browser does not start.
     * @throws Exception if the installer cannot be started.
     */
    private static boolean installBrowser(CliOptions options) throws Exception {
        String pref = options.getBrowser(); // "auto", "firefox", or "chromium"

        String ready = PlaywrightRenderer.findUsableBrowser(options.getTimeoutMs(), pref);
        if (ready != null) {
            System.out.println(ready + " is already available.");
            System.out.println("WebGrep will use it automatically - no installation needed.");
            return true;
        }

        String toInstall;
        if (pref.equals("firefox") || pref.equals("chromium")) {
            toInstall = pref;
        } else {
            System.out.println("No usable browser found. Choose a browser to install:");
            System.out.println("  [1] Firefox  (Mozilla Foundation, ~105 MB)  [default]");
            System.out.println("  [2] Chromium (Google, ~120 MB)");
            System.out.print("Enter choice [1/2] or Enter for Firefox: ");
            java.io.Console console = System.console();
            String line = console != null ? console.readLine() : null;
            toInstall = (line != null && line.trim().equals("2")) ? "chromium" : "firefox";
        }

        String label = toInstall.equals("chromium") ? "Chromium (Google)" : "Firefox (Mozilla)";
        System.out.println("Installing Playwright " + label + " into " + PlaywrightRenderer.browsersPath() + "...");
        int exitCode = PlaywrightRenderer.installBrowser(toInstall);
        if (exitCode != 0) {
            System.err.println("Error: browser installer exited with code " + exitCode + ".");
            return false;
        }
        String installed = PlaywrightRenderer.findUsableBrowser(options.getTimeoutMs(), toInstall);
        if (installed == null) {
            System.err.println("Error: the browser was installed but could not be started.");
            return false;
        }
        System.out.println("Done. WebGrep will use " + installed + " for SPA pages.");
        return true;
    }

    /**
     * Suppresses third-party library logging so that only WebGrep's own output reaches the user.
     *
     * <p>Two logging routes need silencing. Apache Commons Logging (used by PDFBox and other
     * Tika parsers) is bridged to SLF4J, and SLF4J Simple (also used by Playwright) is set to
     * report errors only. The JUL (Java Util Logging) root logger used by various Tika parsers
     * is switched off.
     */
    private static void setupLogging() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        try {
            LogManager.getLogManager().reset();
            Logger rootLogger = Logger.getLogger("");
            rootLogger.setLevel(Level.OFF);
        } catch (Exception ignored) {}
    }
}
