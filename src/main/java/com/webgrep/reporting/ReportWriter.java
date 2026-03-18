package com.webgrep.reporting;

import com.webgrep.config.CliOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ReportWriter {

    public void printTextOutput(CrawlResult crawlResult) {
        Map<String, Integer> results = crawlResult.results;
        int totalCount = results.values().stream().mapToInt(Integer::intValue).sum();

        System.out.println("--- WebGrep Results ---");
        System.out.println("Duration: " + formatDuration(crawlResult.durationMs));
        System.out.println("Total matches found: " + totalCount);
        System.out.println("Pages visited: " + crawlResult.visitedCount);
        System.out.println("Pages successfully parsed: " + crawlResult.parsedCount);

        boolean hasErrors = crawlResult.errorCounts.values().stream().anyMatch(c -> c > 0);
        if (hasErrors) {
            System.out.println("\nIssues:");
            for (CrawlResult.ErrorType type : CrawlResult.ErrorType.values()) {
                int count = crawlResult.errorCounts.get(type);
                if (count > 0) {
                    System.out.println("  " + errorLabel(type) + ": " + count);
                }
            }
        }

        if (totalCount > 0) {
            System.out.println("\nFound in:");
            results.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> System.out.println("  " + entry.getKey() + " (" + entry.getValue() + ")"));
        }

        if (!crawlResult.blockedUrls.isEmpty()) {
            System.out.println("\nBlocked / inaccessible:");
            crawlResult.blockedUrls.forEach((url, reason) ->
                System.out.println("  " + url + " (" + reason + ")")
            );
        }

        if (crawlResult.stoppedAtMaxHits > 0) {
            System.out.println("\nNote: Search stopped early — max-hits limit of "
                    + crawlResult.stoppedAtMaxHits + " reached.");
        }
    }

    public void printJsonOutput(CrawlResult crawlResult, CliOptions options) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"query\": {\n");
        json.append("    \"url\": \"").append(escapeJson(options.getUrl())).append("\",\n");
        json.append("    \"keyword\": \"").append(escapeJson(options.getKeyword())).append("\",\n");
        json.append("    \"depth\": ").append(options.getDepth()).append(",\n");
        json.append("    \"mode\": \"").append(escapeJson(options.getMode())).append("\"\n");
        json.append("  },\n");
        json.append("  \"stats\": {\n");
        json.append("    \"duration_ms\": ").append(crawlResult.durationMs).append(",\n");
        json.append("    \"total_matches\": ").append(crawlResult.results.values().stream().mapToInt(Integer::intValue).sum()).append(",\n");
        json.append("    \"pages_visited\": ").append(crawlResult.visitedCount).append(",\n");
        json.append("    \"pages_parsed\": ").append(crawlResult.parsedCount).append(",\n");
        json.append("    \"pages_blocked\": ").append(crawlResult.blockedUrls.size()).append(",\n");
        json.append("    \"errors\": {\n");
        CrawlResult.ErrorType[] types = CrawlResult.ErrorType.values();
        for (int i = 0; i < types.length; i++) {
            json.append("      \"").append(types[i].name().toLowerCase()).append("\": ").append(crawlResult.errorCounts.get(types[i]));
            if (i < types.length - 1) json.append(",");
            json.append("\n");
        }
        json.append("    }\n");
        json.append("  },\n");
        if (crawlResult.stoppedAtMaxHits > 0) {
            json.append("  \"stopped_early\": \"max-hits limit of ").append(crawlResult.stoppedAtMaxHits).append(" reached\",\n");
        }
        json.append("  \"results\": [\n");

        List<Map.Entry<String, Integer>> sortedResults = new ArrayList<>(crawlResult.results.entrySet());
        sortedResults.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        for (int i = 0; i < sortedResults.size(); i++) {
            Map.Entry<String, Integer> entry = sortedResults.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"count\": ").append(entry.getValue()).append(" }");
            if (i < sortedResults.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"blocked\": [\n");
        List<Map.Entry<String, String>> blockedList = new ArrayList<>(crawlResult.blockedUrls.entrySet());
        for (int i = 0; i < blockedList.size(); i++) {
            Map.Entry<String, String> entry = blockedList.get(i);
            json.append("    { \"url\": \"").append(escapeJson(entry.getKey())).append("\", \"reason\": \"").append(escapeJson(entry.getValue())).append("\" }");
            if (i < blockedList.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        System.out.println(json.toString());
    }

    private String errorLabel(CrawlResult.ErrorType type) {
        return switch (type) {
            case NETWORK_ERROR -> "Network errors";
            case BLOCKED       -> "Blocked";
            case PARSE_ERROR   -> "Parse errors";
            case SKIPPED_SIZE  -> "Skipped (size limit)";
            case SKIPPED_TYPE  -> "Skipped (type filter)";
        };
    }

    private String formatDuration(long ms) {
        if (ms < 60_000) {
            return String.format("%.2fs", ms / 1000.0);
        }
        long minutes = ms / 60_000;
        double seconds = (ms % 60_000) / 1000.0;
        return String.format("%dm %.2fs", minutes, seconds);
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
