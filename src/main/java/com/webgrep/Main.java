package com.webgrep;

import com.webgrep.config.CliOptions;
import com.webgrep.core.ContentExtractor;
import com.webgrep.core.Crawler;
import com.webgrep.core.MatchEngine;
import com.webgrep.reporting.CrawlResult;
import com.webgrep.reporting.ReportWriter;

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
            Crawler crawler = new Crawler(options, extractor, matchEngine);

            long startTime = System.currentTimeMillis();
            CrawlResult result = crawler.crawl();
            result.durationMs = System.currentTimeMillis() - startTime;

            ReportWriter reportWriter = new ReportWriter();
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
            System.err.println("Fatal Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
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