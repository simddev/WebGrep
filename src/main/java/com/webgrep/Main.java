package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.ContentExtractor;
import com.webgrep.core.Crawler;
import com.webgrep.core.MatchEngine;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.ReportWriter;

import java.io.File;
import java.nio.file.Files;
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
            CrawlResult result;

            if (options.getFile() != null) {
                result = scanLocalFile(options, extractor, matchEngine);
            } else {
                Crawler crawler = new Crawler(options, extractor, matchEngine);
                result = crawler.crawl();
            }
            result.durationMs = System.currentTimeMillis() - startTime;

            if ("json".equals(options.getOutput())) {
                reportWriter.printJsonOutput(result, options);
            } else {
                reportWriter.printTextOutput(result);
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

    private static CrawlResult scanLocalFile(CliOptions options, ContentExtractor extractor, MatchEngine matchEngine)
            throws Exception {
        File f = new File(options.getFile());
        if (!f.exists() || !f.isFile())
            throw new IllegalArgumentException("File not found: " + options.getFile());

        byte[] bytes = Files.readAllBytes(f.toPath());
        String text = extractor.extractTextFromBinary(bytes, f.getName(), null);
        int count = matchEngine.countMatches(text, options.getKeyword(), options.getMode());

        CrawlResult result = new CrawlResult();
        result.visitedCount = 1;
        result.parsedCount = 1;
        result.docsCount = 1;
        if (count > 0) result.addMatch(f.getAbsolutePath(), count);
        return result;
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