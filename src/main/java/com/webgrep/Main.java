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
 * WebGrep - A professional CLI web crawler and keyword searcher.
 * @author Simon D.
 */
public class Main {

    public static void main(String[] args) {
        setupLogging();

        try {
            CliOptions options = CliOptions.parse(args);

            if (options.isHelp() || args.length == 0) {
                CliOptions.printHelp();
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
                    reportWriter.printFolderJsonOutput(options, scan.results(), scan.scanned(), scan.skipped(), durationMs);
                } else {
                    reportWriter.printFolderTextOutput(options, scan.results(), scan.scanned(), scan.skipped(), durationMs);
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
                    + (e.getMessage() != null ? " — " + e.getMessage() : ""));
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("  Caused by: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() != null ? " — " + cause.getMessage() : ""));
                cause = cause.getCause();
            }
            System.exit(2);
        }
    }

    private record FolderScan(List<FileScanResult> results, int scanned, int skipped) {}

    private static FolderScan scanFolder(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        File dir = new File(options.getFolder());
        if (!dir.exists() || !dir.isDirectory())
            throw new IllegalArgumentException("Folder not found: " + options.getFolder());

        List<Path> files;
        try (var stream = Files.walk(dir.toPath())) {
            files = stream.filter(Files::isRegularFile).sorted().toList();
        }

        List<FileScanResult> results = new ArrayList<>();
        int scanned = 0, skipped = 0;

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
                }
            } catch (Exception ignored) {
                // unreadable file — skip silently
            }
        }
        System.err.print("\r" + " ".repeat(100) + "\r");
        return new FolderScan(results, scanned, skipped);
    }

    private static List<FileMatch> scanLocalFile(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        File f = new File(options.getFile());
        if (!f.exists() || !f.isFile())
            throw new IllegalArgumentException("File not found: " + options.getFile());

        byte[] bytes = Files.readAllBytes(f.toPath());
        String text = extractor.extractTextFromBinary(bytes, f.getName(), null);
        return findLineMatches(text, options.getKeyword(), options.getMode(), matchEngine);
    }

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